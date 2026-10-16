package za.co.sibusiso.fraud.configuration;

import java.util.Map;
import java.util.Objects;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EvaluationOutcome;
import za.co.sibusiso.fraud.domain.RuleResult;

public record ServiceLimits(int schemaVersion, Api api, Operations operations) {
    public ServiceLimits {
        ContractChecks.require(schemaVersion == 1, "Unsupported service schema version");
        Objects.requireNonNull(api);
        Objects.requireNonNull(operations);
        ContractChecks.require(
                operations.maxProcessingMillis() + (long) operations.commitReserveMillis()
                        <= api.businessDeadlineMillis(),
                "Processing plus commit reserve exceeds business deadline");
        ContractChecks.require(
                api.businessDeadlineMillis() < operations.gracefulShutdownSeconds() * 1000L,
                "Shutdown must outlast the business deadline");
        ContractChecks.require(
                operations.poolAcquireTimeoutMillis()
                                + (long) operations.redisTimeoutMillis()
                                + operations.maxProcessingMillis()
                                + operations.commitReserveMillis()
                        <= api.businessDeadlineMillis(),
                "Configured work budgets exceed the business deadline");
    }

    public record Api(
            int maxRegisteredRules,
            int maxEvidenceFactsPerRule,
            int maxBodyBytes,
            int maxLocationCandidatesPerEvaluation,
            boolean documentationEnabled,
            int maxJsonDepth,
            int maxJsonNumberLength,
            int maxIdentifierLength,
            int maxCategoryLength,
            int maxCityLength,
            int maxCursorLength,
            int defaultPageSize,
            int maxPageSize,
            int defaultQueryRangeDays,
            int maxQueryRangeDays,
            int businessDeadlineMillis,
            int edgeDeadlineMillis,
            int exampleClientDeadlineMillis,
            RateLimits rateLimits) {
        public Api {
            bounded(maxRegisteredRules, EvaluationOutcome.MAX_RULES, "Rule count");
            bounded(maxEvidenceFactsPerRule, RuleResult.MAX_EVIDENCE_FACTS, "Evidence facts");
            bounded(maxBodyBytes, 16384, "Body bytes");
            ContractChecks.require(
                    maxLocationCandidatesPerEvaluation > 0
                            && maxLocationCandidatesPerEvaluation < Integer.MAX_VALUE,
                    "Invalid location candidate safety ceiling");
            bounded(maxJsonDepth, 8, "JSON depth");
            bounded(maxJsonNumberLength, 32, "JSON number length");
            bounded(maxIdentifierLength, 128, "Identifier length");
            bounded(maxCategoryLength, 64, "Category length");
            bounded(maxCityLength, 128, "City length");
            bounded(maxCursorLength, 2048, "Cursor length");
            bounded(maxPageSize, 100, "Page size");
            bounded(defaultPageSize, maxPageSize, "Default page size");
            bounded(maxQueryRangeDays, 31, "Query range");
            bounded(defaultQueryRangeDays, maxQueryRangeDays, "Default query range");
            positive(businessDeadlineMillis, "Business deadline");
            ContractChecks.require(
                    businessDeadlineMillis < edgeDeadlineMillis
                            && edgeDeadlineMillis < exampleClientDeadlineMillis,
                    "Deadline order must be business < edge < client");
            Objects.requireNonNull(rateLimits);
        }
    }

    public record Bucket(int capacity, int refillPerSecond) {
        public Bucket {
            positive(capacity, "Bucket capacity");
            positive(refillPerSecond, "Bucket refill");
        }
    }

    public record RateLimits(Bucket ingest, Bucket read, Bucket policy) {
        public RateLimits {
            Objects.requireNonNull(ingest);
            Objects.requireNonNull(read);
            Objects.requireNonNull(policy);
        }
    }

    public record Operations(
            int dbPoolMaxConnectionsPerReplica,
            int maxConcurrentRequestsPerReplica,
            int httpMinSpareThreads,
            int maxQueuedRequestsPerReplica,
            int httpMaxHeaderBytes,
            int httpConnectionTimeoutMillis,
            int poolAcquireTimeoutMillis,
            int lockTimeoutMillis,
            int statementTimeoutMillis,
            int redisTimeoutMillis,
            int commitReserveMillis,
            int maxProcessingMillis,
            int gracefulShutdownSeconds,
            int containerStopGraceSeconds,
            int maxClockRegressionMillis) {
        public Operations {
            positive(dbPoolMaxConnectionsPerReplica, "Database pool");
            positive(maxConcurrentRequestsPerReplica, "Concurrent requests");
            bounded(httpMinSpareThreads, maxConcurrentRequestsPerReplica, "Minimum spare threads");
            bounded(httpMaxHeaderBytes, 65536, "HTTP header bytes");
            positive(httpConnectionTimeoutMillis, "HTTP connection timeout");
            ContractChecks.require(
                    maxQueuedRequestsPerReplica > 0
                            && (long) maxConcurrentRequestsPerReplica + maxQueuedRequestsPerReplica
                                    <= Integer.MAX_VALUE,
                    "Socket backlog must be positive and fit the connection limit");
            ContractChecks.require(
                    poolAcquireTimeoutMillis >= 500,
                    "Pool acquisition must allow Hikari validation of at least 250ms");
            positive(lockTimeoutMillis, "Lock timeout");
            ContractChecks.require(
                    lockTimeoutMillis <= statementTimeoutMillis
                            && statementTimeoutMillis < maxProcessingMillis,
                    "Timeout order must be lock <= statement < processing");
            positive(redisTimeoutMillis, "Redis timeout");
            positive(commitReserveMillis, "Commit reserve");
            ContractChecks.require(
                    gracefulShutdownSeconds > 0
                            && containerStopGraceSeconds > gracefulShutdownSeconds,
                    "Container stop grace must outlast application shutdown");
            ContractChecks.require(
                    maxClockRegressionMillis >= 0, "Clock regression tolerance cannot be negative");
        }
    }

    public Map<String, Object> runtimeProperties() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put(
                "fraud.transaction-timeout-seconds",
                seconds(
                        Math.addExact(
                                operations.maxProcessingMillis(),
                                operations.commitReserveMillis())));
        result.put(
                "spring.datasource.hikari.maximum-pool-size",
                operations.dbPoolMaxConnectionsPerReplica());
        result.put(
                "spring.datasource.hikari.connection-timeout",
                operations.poolAcquireTimeoutMillis());
        result.put(
                "spring.datasource.hikari.validation-timeout",
                operations.poolAcquireTimeoutMillis() / 2);
        result.put("spring.datasource.hikari.initialization-fail-timeout", -1);
        result.put(
                "spring.datasource.hikari.connection-init-sql",
                "SET statement_timeout = '"
                        + operations.statementTimeoutMillis()
                        + "ms'; SET lock_timeout = '"
                        + operations.lockTimeoutMillis()
                        + "ms'");
        result.put(
                "spring.datasource.hikari.data-source-properties.connectTimeout",
                seconds(operations.poolAcquireTimeoutMillis()));
        result.put(
                "spring.datasource.hikari.data-source-properties.socketTimeout",
                seconds(
                        Math.max(
                                operations.statementTimeoutMillis(),
                                operations.commitReserveMillis())));
        result.put("spring.data.redis.connect-timeout", operations.redisTimeoutMillis() + "ms");
        result.put("spring.data.redis.timeout", operations.redisTimeoutMillis() + "ms");
        result.put(
                "spring.lifecycle.timeout-per-shutdown-phase",
                operations.gracefulShutdownSeconds() + "s");
        result.put("server.max-http-request-header-size", operations.httpMaxHeaderBytes() + "B");
        result.put("server.tomcat.max-swallow-size", api.maxBodyBytes() + "B");
        result.put(
                "server.tomcat.connection-timeout",
                operations.httpConnectionTimeoutMillis() + "ms");
        result.put(
                "server.tomcat.keep-alive-timeout",
                operations.httpConnectionTimeoutMillis() + "ms");
        result.put("server.tomcat.threads.max", operations.maxConcurrentRequestsPerReplica());
        result.put("server.tomcat.threads.min-spare", operations.httpMinSpareThreads());
        result.put("server.tomcat.accept-count", operations.maxQueuedRequestsPerReplica());
        result.put(
                "server.tomcat.max-connections",
                operations.maxConcurrentRequestsPerReplica()
                        + operations.maxQueuedRequestsPerReplica());
        return Map.copyOf(result);
    }

    private static int seconds(int millis) {
        return (int) ((millis + 999L) / 1000L);
    }

    private static void positive(int value, String name) {
        ContractChecks.require(value > 0, name + " must be positive");
    }

    private static void bounded(int value, int maximum, String name) {
        ContractChecks.require(value > 0 && value <= maximum, name + " exceeds v1 bounds");
    }
}
