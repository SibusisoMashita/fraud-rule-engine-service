package za.co.sibusiso.fraud.domain;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvaluationOutcome(
        Decision decision, Coverage coverage, List<RuleResult> ruleResults) {
    public static final int MAX_RULES = 64;

    public enum Decision {
        CLEAR,
        FLAGGED,
        INDETERMINATE
    }

    public enum Coverage {
        COMPLETE,
        PARTIAL
    }

    public record ExpectedRule(String algorithmVersion, boolean enabled) {
        public ExpectedRule {
            algorithmVersion = ContractChecks.text(algorithmVersion, 32);
        }
    }

    public EvaluationOutcome {
        Objects.requireNonNull(decision);
        Objects.requireNonNull(coverage);
        ruleResults = List.copyOf(ruleResults);
        Summary summary = summarize(ruleResults);
        ContractChecks.require(
                decision == summary.decision() && coverage == summary.coverage(),
                "Decision/coverage disagree with rule results");
    }

    public static EvaluationOutcome aggregate(
            Map<RuleId, ExpectedRule> expected,
            Collection<RuleResult> results,
            int maxRules,
            int maxEvidenceFacts) {
        expected = Map.copyOf(expected);
        ContractChecks.require(
                maxRules > 0
                        && maxRules <= MAX_RULES
                        && maxEvidenceFacts > 0
                        && maxEvidenceFacts <= RuleResult.MAX_EVIDENCE_FACTS,
                "Invalid result limits");
        ContractChecks.require(
                expected.size() <= maxRules && results.size() == expected.size(),
                "Rule result set is incomplete or exceeds the configured limit");
        for (RuleResult result : results) {
            ExpectedRule rule = expected.get(result.ruleId());
            ContractChecks.require(
                    rule != null && rule.algorithmVersion().equals(result.algorithmVersion()),
                    "Unexpected rule or algorithm version");
            ContractChecks.require(
                    rule.enabled() == (result.status() != RuleResult.Status.DISABLED),
                    "Rule status disagrees with enabled policy");
            ContractChecks.require(
                    result.evidence().size() <= maxEvidenceFacts,
                    "Evidence exceeds configured limit");
        }
        List<RuleResult> ordered =
                results.stream().sorted((a, b) -> a.ruleId().compareTo(b.ruleId())).toList();
        Summary summary = summarize(ordered);
        return new EvaluationOutcome(summary.decision(), summary.coverage(), ordered);
    }

    private static Summary summarize(List<RuleResult> results) {
        ContractChecks.require(
                !results.isEmpty() && results.size() <= MAX_RULES, "Invalid rule result count");
        var ids = new HashSet<RuleId>();
        boolean match = false;
        boolean insufficient = false;
        boolean enabled = false;
        for (RuleResult result : results) {
            ContractChecks.require(ids.add(result.ruleId()), "Duplicate rule result");
            enabled |= result.status() != RuleResult.Status.DISABLED;
            match |= result.status() == RuleResult.Status.MATCH;
            insufficient |= result.status() == RuleResult.Status.INSUFFICIENT_DATA;
        }
        ContractChecks.require(enabled, "All-disabled result sets are invalid");
        return new Summary(
                match ? Decision.FLAGGED : insufficient ? Decision.INDETERMINATE : Decision.CLEAR,
                insufficient ? Coverage.PARTIAL : Coverage.COMPLETE);
    }

    private record Summary(Decision decision, Coverage coverage) {}
}
