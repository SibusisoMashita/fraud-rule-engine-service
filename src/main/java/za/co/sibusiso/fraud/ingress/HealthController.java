package za.co.sibusiso.fraud.ingress;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sibusiso.fraud.policy.ActivePolicyProvider;

@RestController
@Profile("!migrate & !policy-admin")
class HealthController {
    private final ActivePolicyProvider policy;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    HealthController(JdbcTemplate jdbc, StringRedisTemplate redis, ActivePolicyProvider policy) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.redis = redis;
    }

    @GetMapping(ApiPaths.LIVE)
    Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping(ApiPaths.READY)
    ResponseEntity<Map<String, String>> ready() {
        try {
            policy.current();
            Boolean schemaReady =
                    jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM fraud.service_metadata WHERE version ="
                                + " 9)",
                            Boolean.class);
            String pong = redis.execute(connection -> connection.ping(), true);
            if (Boolean.TRUE.equals(schemaReady) && "PONG".equals(pong)) {
                return ResponseEntity.ok(Map.of("status", "UP"));
            }
        } catch (RuntimeException unavailable) {
            // Public probes deliberately omit dependency names, credentials and diagnostics.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }
}
