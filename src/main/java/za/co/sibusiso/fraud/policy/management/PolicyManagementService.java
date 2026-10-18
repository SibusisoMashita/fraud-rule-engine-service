package za.co.sibusiso.fraud.policy.management;

import java.sql.SQLException;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.evaluation.rules.RuleEngine;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ApiFailure.Outcome;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;

/** Shared validation and conditional activation; fraud calculations stay in the rule engine. */
public final class PolicyManagementService {
    public record Active(long revision, String policyHash, PolicyLoader.Document document) {}
    public record Validation(String policyHash) {}
    private record Candidate(String hash, String text) {}
    private static final String STALE_REVISION = "P0412";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate runtime;
    private final JdbcPolicyWriter writer;
    private final StrictYaml yaml;
    private final PolicyLoader loader;
    private final RuleEngine engine;

    public PolicyManagementService(JdbcTemplate runtime, JdbcPolicyWriter writer,
            StrictYaml yaml, PolicyLoader loader, RuleEngine engine) {
        this.runtime = runtime;
        this.writer = writer;
        this.yaml = yaml;
        this.loader = loader;
        this.engine = engine;
    }

    public Active current(Caller caller, Runnable budget) {
        authorize(caller);
        budget.run();
        try {
            var active = runtime.queryForObject(
                    "SELECT h.revision, h.policy_hash, v.policy_yaml FROM fraud.policy_head h"
                            + " JOIN fraud.policy_versions v ON v.policy_hash=h.policy_hash WHERE h.singleton",
                    (row, index) -> new Active(row.getLong("revision"), row.getString("policy_hash"),
                            verifiedDocument(row.getString("policy_yaml"), row.getString("policy_hash"))));
            Objects.requireNonNull(active, "Active policy is required");
            budget.run();
            return active;
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException | NullPointerException | JacksonException failure) {
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS, failure);
        }
    }

    public Validation validate(Caller caller, PolicyLoader.Document document, Runnable budget) {
        authorize(caller);
        budget.run();
        var candidate = candidate(document);
        budget.run();
        return new Validation(candidate.hash());
    }

    public Active activate(Caller caller, PolicyLoader.Document document, long revision, Runnable budget) {
        authorize(caller);
        budget.run();
        var candidate = candidate(document);
        budget.run();
        try {
            // A privileged CLI/database mistake must not be reactivated through the validated API.
            runtime.queryForList("SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash = ?",
                    String.class, candidate.hash()).forEach(text -> verifiedDocument(text, candidate.hash()));
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException | NullPointerException | JacksonException failure) {
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS, failure);
        }
        budget.run();
        try {
            var activated = writer.activate(candidate.hash(), candidate.text(), revision, caller.principalId());
            // Versions are immutable: a same-hash no-op/re-activation returns its stored document.
            var stored = verifiedDocument(runtime.queryForObject(
                    "SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash = ?",
                    String.class, activated.policyHash()), activated.policyHash());
            return new Active(activated.revision(), activated.policyHash(), stored);
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && STALE_REVISION.equals(sql.getSQLState()))
                    throw new ApiFailure(HttpStatus.PRECONDITION_FAILED,
                            ErrorCode.POLICY_REVISION_CONFLICT, 0, failure);
            }
            // A failed acknowledgement cannot prove whether the single database call committed.
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.POLICY_OUTCOME_UNKNOWN,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS, Outcome.UNKNOWN, failure);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | JacksonException failure) {
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.POLICY_OUTCOME_UNKNOWN,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS, Outcome.UNKNOWN, failure);
        }
    }

    private PolicyLoader.Document verifiedDocument(String text, String hash) {
        var document = yaml.read(text, PolicyLoader.Document.class);
        var snapshot = loader.read(document);
        engine.verify(snapshot);
        if (!snapshot.policyHash().equals(hash))
            throw new IllegalStateException("Stored policy hash differs from its content");
        return document;
    }

    private Candidate candidate(PolicyLoader.Document document) {
        try {
            var snapshot = loader.read(document);
            engine.verify(snapshot);
            return new Candidate(snapshot.policyHash(), JSON.writeValueAsString(document));
        } catch (IllegalArgumentException | NullPointerException | JacksonException failure) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.POLICY_INVALID, 0, failure);
        }
    }

    private static void authorize(Caller caller) {
        if (!caller.roles().contains(Caller.Role.POLICY_ADMIN))
            throw new ApiFailure(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    }
}
