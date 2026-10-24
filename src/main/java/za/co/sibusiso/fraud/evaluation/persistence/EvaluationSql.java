package za.co.sibusiso.fraud.evaluation.persistence;

/** One response representation for immutable evaluations in point and page reads. */
final class EvaluationSql {
    private EvaluationSql() {}

    static final String DOCUMENT =
            """
            jsonb_build_object('id', e.id, 'eventId', e.event_id, 'policyHash', e.policy_hash,
                        'buildId', e.build_id, 'decision', e.decision, 'coverage', e.coverage,
                        'acceptedAt', to_char(t.accepted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                        'evaluatedAt', to_char(e.evaluated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                        'contextHighWatermark', e.context_high_watermark,
                        'ruleResults', (SELECT jsonb_agg(jsonb_build_object('ruleId', r.rule_id,
                            'algorithmVersion', r.algorithm_version, 'status', r.status, 'reasonCode', r.reason_code,
                            'evidence', r.evidence) ORDER BY r.rule_id)
                            FROM fraud.rule_results r WHERE r.scope=t.scope AND r.event_id=t.event_id))
            """;
}
