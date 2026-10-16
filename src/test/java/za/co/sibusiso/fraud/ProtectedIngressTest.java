package za.co.sibusiso.fraud;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.ingress.security.CredentialAuthenticator;
import za.co.sibusiso.fraud.ingress.security.IngressRoute;
import za.co.sibusiso.fraud.ingress.security.SharedRateLimiter;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProtectedIngressTest {
    private static final String PASSWORD = TestDatabase.PASSWORD;
    private static final String SECRET = "a".repeat(64);
    private static final String BODY = """
            {"eventId":"evt-1","customerId":"customer-1","instrumentId":"card-1","transactionType":"CARD_PURCHASE",
             "status":"BOOKED","channel":"ECOMMERCE","amount":"1.00","currency":"ZAR","category":"GROCERIES",
             "occurredAt":"%s"}
            """.formatted(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(DockerImageName.parse(
            TestImages.POSTGRES).asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            TestImages.REDIS))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD, "--maxmemory-policy", "noeviction");
    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry properties) throws Exception {
        TestDatabase.initialize(DATABASE);
        properties.add("fraud.cursor-signing-key",()->"a".repeat(64));
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password", () -> PASSWORD);
    }
    @LocalServerPort int port;
    @Autowired CredentialAuthenticator authenticator;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate runtime;
    @Autowired org.springframework.boot.web.server.context.WebServerApplicationContext application;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private record Identity(String keyId, String principal, String scope) { String key() { return keyId + "." + SECRET; } }
    private JdbcTemplate admin() { return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "fraud_security_admin", PASSWORD)); }
    private Identity identity(String roles, String sources) {
        String id = UUID.randomUUID().toString();
        var identity = new Identity(id, "principal-" + id, "scope-" + id);
        admin().update("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,?::text[],?::text[])",
                identity.principal(), identity.scope(), roles, sources);
        credential(identity.keyId(), identity.principal());
        return identity;
    }
    private void credential(String keyId, String principal) { credential(keyId,principal,SECRET); }
    private void credential(String keyId, String principal, String secret) {
        admin().update("INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')",
                keyId, principal, HexFormat.of().formatHex(CredentialAuthenticator.digest(secret)));
    }
    @Test void operationalBoundsReachTheRunningHttpConnector() {
        var server=(org.springframework.boot.tomcat.TomcatWebServer)application.getWebServer();
        var handler=(org.apache.coyote.http11.AbstractHttp11Protocol<?>)server.getTomcat().getConnector().getProtocolHandler();
        assertThat(handler.getMaxHttpRequestHeaderSize()).isEqualTo(8192);
        assertThat(handler.getMaxSwallowSize()).isEqualTo(16384);
        assertThat(handler.getConnectionTimeout()).isEqualTo(5000);
        assertThat(handler.getKeepAliveTimeout()).isEqualTo(5000);
        assertThat(handler.getMaxThreads()).isEqualTo(20);
        assertThat(handler.getMaxConnections()).isEqualTo(40);
    }
    @Test void authenticatesOnlyLiveCredentialsWithSafeUniformErrorsAndNoSession() throws Exception {
        var identity = identity("{READ}", "{}");
        assertStatus(call("GET", "/api/v1/evaluations", null, null),401,"INVALID_CREDENTIAL");
        assertStatus(call("GET", "/api/v1/evaluations", "unknown."+SECRET, null),401,"INVALID_CREDENTIAL");
        assertStatus(call("GET", "/api/v1/evaluations", identity.keyId()+"."+"b".repeat(64), null),401,"INVALID_CREDENTIAL");
        var admitted = call("GET", "/api/v1/evaluations", identity.key(), null);
        assertQuery(admitted);
        assertThat(admitted.headers().firstValue("set-cookie")).isEmpty();
        assertThat(admitted.headers().firstValue("Cache-Control")).contains("no-store");
        var duplicate = http.send(request("GET", "/api/v1/evaluations", identity.key(), null).header("X-API-Key", identity.key()).build(), HttpResponse.BodyHandlers.ofString());
        assertStatus(duplicate,401,"INVALID_CREDENTIAL");
        admin().update("UPDATE fraud.api_credentials SET revoked=true WHERE key_id=?",identity.keyId());
        assertStatus(call("GET", "/api/v1/evaluations", identity.key(), null),401,"INVALID_CREDENTIAL");
        admin().update("UPDATE fraud.api_credentials SET revoked=false, expires_at=clock_timestamp()-interval '1 second' WHERE key_id=?",identity.keyId());
        assertStatus(call("GET", "/api/v1/evaluations", identity.key(), null),401,"INVALID_CREDENTIAL");
        admin().update("UPDATE fraud.api_credentials SET valid_from=clock_timestamp()+interval '1 minute', expires_at=clock_timestamp()+interval '1 hour' WHERE key_id=?",identity.keyId());
        assertStatus(call("GET", "/api/v1/evaluations", identity.key(), null),401,"INVALID_CREDENTIAL");
    }
    @Test void derivesScopeAndPermissionsFromDatabaseAndAppliesChangesWithoutRestart() throws Exception {
        var first = identity("{READ}","{}");
        var second = identity("{INGEST}","{}");
        assertThat(authenticator.authenticate(first.key()).scope()).isEqualTo(first.scope()).isNotEqualTo(second.scope());
        assertStatus(call("POST","/api/v1/transactions",first.key(),BODY),403,"FORBIDDEN");
        assertStatus(call("GET","/api/v1/rules",first.key(),null),403,"FORBIDDEN");
        long version = authenticator.authenticate(first.key()).permissionVersion();
        admin().update("UPDATE fraud.api_principals SET roles=ARRAY['INGEST','POLICY'] WHERE principal_id=?",first.principal());
        assertThat(authenticator.authenticate(first.key()).permissionVersion()).isEqualTo(version+1);
        assertAccepted(call("POST","/api/v1/transactions",first.key(),BODY));
        assertStatus(call("GET","/api/v1/evaluations",first.key(),null),403,"FORBIDDEN");
        assertQuery(call("GET","/api/v1/rules",first.key(),null));
        var forged = http.send(request("POST","/api/v1/transactions",first.key(),BODY).header("X-Scope",second.scope())
                .header("X-Forwarded-For","127.0.0.1").build(),HttpResponse.BodyHandlers.ofString());
        assertAccepted(forged);
        assertThat(authenticator.authenticate(first.key()).scope()).isEqualTo(first.scope());
        admin().update("UPDATE fraud.api_principals SET enabled=false WHERE principal_id=?",first.principal());
        assertStatus(call("POST","/api/v1/transactions",first.key(),BODY),401,"INVALID_CREDENTIAL");
    }
    @Test void trustedPhysicalSourcesRequireCurrentProducerPermission() throws Exception {
        var identity = identity("{INGEST}","{}");
        String physical = BODY.replace("ECOMMERCE","CARD_PRESENT").replace("\"amount\":", "\"physicalLocation\":{\"latitude\":-33.9249,\"longitude\":18.4241,\"accuracyKm\":0.1,\"source\":\"VERIFIED_CARD_PRESENT_TERMINAL\",\"city\":\"Cape Town\"},\"amount\":");
        assertStatus(call("POST","/api/v1/transactions",identity.key(),physical),403,"LOCATION_SOURCE_FORBIDDEN");
        admin().update("UPDATE fraud.api_principals SET location_sources=ARRAY['VERIFIED_CARD_PRESENT_TERMINAL'] WHERE principal_id=?",identity.principal());
        assertAccepted(call("POST","/api/v1/transactions",identity.key(),physical));
        admin().update("UPDATE fraud.api_principals SET location_sources=ARRAY[]::text[] WHERE principal_id=?",identity.principal());
        assertStatus(call("POST","/api/v1/transactions",identity.key(),physical),403,"LOCATION_SOURCE_FORBIDDEN");
        assertStatus(call("POST","/api/v1/transactions",identity.key(),physical.replace("\"channel\":\"CARD_PRESENT\"","\"channel\":\"ECOMMERCE\"")),422,"INVALID_TRANSACTION");
    }
    @Test void parserRejectsAttacksBeforeAnyFinancialStateCanBeAccepted() throws Exception {
        var identity = identity("{INGEST}","{}");
        assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY.replace("\"amount\":","\"scope\":\"other\",\"amount\":")),400,"INVALID_FIELD");
        assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY.replace("\"eventId\":","\"eventId\":\"duplicate\",\"eventId\":")),400,"INVALID_JSON");
        assertStatus(call("POST","/api/v1/transactions",identity.key(),"x".repeat(16385)),413,"BODY_TOO_LARGE");
        var noLength = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream("x".repeat(16385).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var chunked = http.send(HttpRequest.newBuilder(uri("/api/v1/transactions")).timeout(Duration.ofSeconds(5))
                .header("X-API-Key",identity.key()).header("Content-Type","application/json").POST(noLength).build(),HttpResponse.BodyHandlers.ofString());
        assertStatus(chunked,413,"BODY_TOO_LARGE");
        var compressed = http.send(request("POST","/api/v1/transactions",identity.key(),BODY).header("Content-Encoding","gzip").build(),HttpResponse.BodyHandlers.ofString());
        assertStatus(compressed,415,"UNSUPPORTED_MEDIA_TYPE");
        assertStatus(call("POST","/api/v1/transactions?scope=other",identity.key(),BODY),400,"UNSUPPORTED_QUERY");
        assertStatus(call("GET","/actuator/env",identity.key(),null),403,"FORBIDDEN");
        assertThat(call("GET","/health/live",null,null).statusCode()).isEqualTo(200);
        assertThat(runtime.queryForObject("SELECT count(*) FROM fraud.transactions WHERE scope=?",Integer.class,identity.scope())).isZero();
    }
    @Test void duplicateMediaHeadersCannotBypassContentRestrictions() throws Exception {
        var identity = identity("{INGEST}", "{}");
        for (String value : new String[]{"text/plain", "application/json"}) {
            var response = http.send(request("POST", "/api/v1/transactions", identity.key(), BODY)
                    .header("Content-Type", value).build(), HttpResponse.BodyHandlers.ofString());
            assertStatus(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        }
        var response = http.send(request("POST", "/api/v1/transactions", identity.key(), BODY)
                .header("Content-Encoding", "identity").header("Content-Encoding", "gzip").build(),
                HttpResponse.BodyHandlers.ofString());
        assertStatus(response, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test void exhaustedQuotaIsIsolatedByPrincipalAndRoute() throws Exception {
        var first = identity("{INGEST,READ,POLICY}", "{}");
        var second = identity("{INGEST,READ,POLICY}", "{}");
        Caller caller = authenticator.authenticate(first.key());
        String key = SharedRateLimiter.key(caller, IngressRoute.INGEST);
        redis.opsForHash().putAll(key, Map.of("tokens", "0", "time", Long.toString(System.currentTimeMillis() + 60000)));
        assertStatus(call("POST", "/api/v1/transactions", first.key(), BODY), 429, "RATE_LIMITED");
        assertQuery(call("GET", "/api/v1/evaluations", first.key(), null));
        assertQuery(call("GET", "/api/v1/rules", first.key(), null));
        assertAccepted(call("POST", "/api/v1/transactions", second.key(), BODY));
        var forged = http.send(request("POST", "/api/v1/transactions", first.key(), BODY)
                .header("X-Principal-ID", second.principal()).header("X-Scope", second.scope())
                .header("X-Forwarded-For", "192.0.2.1").build(), HttpResponse.BodyHandlers.ofString());
        assertStatus(forged, 429, "RATE_LIMITED");
        String readKey = SharedRateLimiter.key(caller, IngressRoute.READ);
        redis.opsForHash().putAll(readKey, Map.of("tokens", "0", "time", Long.toString(System.currentTimeMillis() + 60000)));
        assertStatus(call("GET", "/api/v1/evaluations", first.key(), null), 429, "RATE_LIMITED");
        assertStatus(call("GET", "/api/v1/transactions/evt-1", first.key(), null), 429, "RATE_LIMITED");
        assertQuery(call("GET", "/api/v1/rules", first.key(), null));
    }

    @Test void twoLimiterInstancesShareAtomicQuotaAndRotationCannotResetIt() throws Exception {
        var identity = identity("{INGEST}","{}");
        String rotated = UUID.randomUUID().toString(); String rotatedSecret="b".repeat(64); credential(rotated,identity.principal(),rotatedSecret);
        Caller caller = authenticator.authenticate(identity.key());
        Caller rotatedCaller = authenticator.authenticate(rotated+"."+rotatedSecret);
        assertThat(rotatedCaller).isEqualTo(caller);
        var yaml = new StrictYaml();
        String limitsText = yaml.readText(new org.springframework.core.io.ClassPathResource("config/service.example.yaml"))
                .replace("ingest: {capacity: 30, refillPerSecond: 5}","ingest: {capacity: 4, refillPerSecond: 1}");
        var limits = yaml.read(limitsText,ServiceLimits.class);
        var first = new SharedRateLimiter(redis,limits); var second = new SharedRateLimiter(redis,limits);
        String key = SharedRateLimiter.key(caller,IngressRoute.INGEST);
        redis.opsForHash().putAll(key,Map.of("tokens","4","time",Long.toString(System.currentTimeMillis()+60000)));
        var allowed = new AtomicInteger(); var denied = new AtomicInteger(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<?>>();
            for (int i=0;i<20;i++) { final int index=i; futures.add(executor.submit(() -> {
                try { start.await(); (index%2==0?first:second).acquire(index%2==0?caller:rotatedCaller,IngressRoute.INGEST); allowed.incrementAndGet(); }
                catch (ApiFailure failure) { assertThat(failure.status().value()).isEqualTo(429); assertThat(failure.retryAfterSeconds()).isPositive(); denied.incrementAndGet(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            })); }
            start.countDown(); for (var future:futures) future.get(5,TimeUnit.SECONDS);
        }
        assertThat(allowed).hasValue(4); assertThat(denied).hasValue(16);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,4001L);
        redis.expire(key,Duration.ofMinutes(1));
        assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY),429,"RATE_LIMITED");
        assertStatus(call("POST","/api/v1/transactions",rotated+"."+rotatedSecret,BODY),429,"RATE_LIMITED");
        assertThat(call("GET","/api/v1/evaluations",identity.key(),null).statusCode()).isEqualTo(403);
    }
    @Test void limiterCapsRefillsClampsRegressionsAndExpiresIdleState() throws Exception {
        var identity=identity("{INGEST}","{}"); Caller caller=authenticator.authenticate(identity.key());
        var limits=new StrictYaml().read(new org.springframework.core.io.ClassPathResource("config/service.example.yaml"),ServiceLimits.class);
        var limiter=new SharedRateLimiter(redis,limits);String key=SharedRateLimiter.key(caller,IngressRoute.INGEST);
        long future=System.currentTimeMillis()+60000;
        redis.opsForHash().putAll(key,Map.of("tokens","1000","time",Long.toString(future)));
        limiter.acquire(caller,IngressRoute.INGEST);
        assertThat(redis.opsForHash().get(key,"tokens")).isEqualTo("29");
        assertThat(redis.opsForHash().get(key,"time")).isEqualTo(Long.toString(future));
        redis.opsForHash().putAll(key,Map.of("tokens","0","time",Long.toString(System.currentTimeMillis()-60000)));
        limiter.acquire(caller,IngressRoute.INGEST);
        assertThat(redis.opsForHash().get(key,"tokens")).isEqualTo("29");
        redis.expire(key,Duration.ofMillis(1));Thread.sleep(20);
        limiter.acquire(caller,IngressRoute.INGEST);
        assertThat(redis.opsForHash().get(key,"tokens")).isEqualTo("29");
    }
    @Test void invalidCredentialsCreateNoLimiterStateAndDependencyFailuresStayClosed() throws Exception {
        var identity=identity("{INGEST}","{}");Caller caller=authenticator.authenticate(identity.key());
        String key=SharedRateLimiter.key(caller,IngressRoute.INGEST);
        assertStatus(call("POST","/api/v1/transactions",identity.keyId()+"."+"f".repeat(64),BODY),401,"INVALID_CREDENTIAL");
        assertThat(redis.hasKey(key)).isFalse();
        redis.opsForValue().set(key,"wrong-type");
        assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY),503,"LIMITER_UNAVAILABLE");
        redis.delete(key);
        var docker=REDIS.getDockerClient();docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try { assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY),503,"LIMITER_UNAVAILABLE");
            assertThat(call("GET","/health/live",null,null).statusCode()).isEqualTo(200);
        } finally { docker.unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        assertAccepted(call("POST","/api/v1/transactions",identity.key(),BODY));
        var dbDocker=DATABASE.getDockerClient();dbDocker.pauseContainerCmd(DATABASE.getContainerId()).exec();
        try { assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY),503,"DEPENDENCY_UNAVAILABLE"); }
        finally { dbDocker.unpauseContainerCmd(DATABASE.getContainerId()).exec(); }
        assertAccepted(call("POST","/api/v1/transactions",identity.key(),BODY));
    }
    @Test void redisMemoryExhaustionRejectsTrafficAndRecoversWithoutRestart() throws Exception {
        var identity=identity("{INGEST}","{}");
        redis.execute(connection -> { connection.serverCommands().setConfig("maxmemory","1"); return true; }, true);
        try { assertStatus(call("POST","/api/v1/transactions",identity.key(),BODY),503,"LIMITER_UNAVAILABLE"); }
        finally { redis.execute(connection -> { connection.serverCommands().setConfig("maxmemory","0"); return true; }, true); }
        assertAccepted(call("POST","/api/v1/transactions",identity.key(),BODY));
    }
    @Test void databaseGuardsIdentityScopeAndAuditWithSeparateRuntimePrivileges() {
        var identity=identity("{READ}","{}");
        assertThatThrownBy(() -> admin().update("UPDATE fraud.api_principals SET scope='changed' WHERE principal_id=?",identity.principal())).rootCause().hasMessageContaining("immutable");
        assertThatThrownBy(() -> admin().update("UPDATE fraud.api_credentials SET secret_hash=? WHERE key_id=?","b".repeat(64),identity.keyId())).rootCause().hasMessageContaining("Rotate");
        assertThatThrownBy(() -> runtime.update("UPDATE fraud.api_principals SET enabled=false WHERE principal_id=?",identity.principal())).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> admin().update("DELETE FROM fraud.identity_changes")).rootCause().hasMessageContaining("permission denied");
        assertThat(admin().queryForObject("SELECT count(*) FROM fraud.identity_changes WHERE entity_id IN (?,?) AND changed_by='fraud_security_admin'",Integer.class,identity.principal(),identity.keyId())).isEqualTo(2);
        admin().update("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['READ'],ARRAY[]::text[]) ON CONFLICT DO NOTHING",identity.principal(),identity.scope());
        assertThat(admin().queryForObject("SELECT count(*) FROM fraud.identity_changes WHERE entity_id=?",Integer.class,identity.principal())).isEqualTo(1);
    }
    private HttpRequest.Builder request(String method,String path,String key,String body) {
        var builder=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if(key!=null) builder.header("X-API-Key",key);
        if(body!=null) builder.header("Content-Type","application/json");
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
    }
    private HttpResponse<String> call(String method,String path,String key,String body) throws Exception {
        return http.send(request(method,path,key,body).build(),HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) {return URI.create("http://localhost:"+port+path);}
    private void assertAccepted(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isIn(200,201);
        var body=JsonMapper.builder().build().readTree(response.body());
        assertThat(body.get("evaluation").get("decision").asString()).isIn("CLEAR","FLAGGED","INDETERMINATE");
    }
    private void assertQuery(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
    private void assertStatus(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(type->assertThat(type).startsWith("application/problem+json"));
        var problem=JsonMapper.builder().build().readTree(response.body());
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("outcome").asString()).isEqualTo("NOT_ACCEPTED");
        assertThat(problem.get("correlationId").asString()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertThat(response.body()).doesNotContain(SECRET,"synthetic-test-password","SQLException","redis://");
        if(status==401) assertThat(response.headers().firstValue("WWW-Authenticate")).contains("ApiKey");
        if(status==429 || code.endsWith("UNAVAILABLE")) assertThat(response.headers().firstValue("Retry-After")).contains("1");
    }
}
