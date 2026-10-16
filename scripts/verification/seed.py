"""Bulk relational history with canonical request identities and labelled fixture results."""
import hashlib
import json
import textwrap
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from recovery import invariant_counts
from stack import check


def run(stack):
    settings = stack.settings
    total, customers, principals = (settings[key] for key in ('seedEvents', 'customers', 'principals'))
    check(total % customers == 0 and customers % principals == 0, 'Seed must divide into equal per-scope customer histories')
    occurred = (datetime.now(timezone.utc) - timedelta(seconds=total // customers)).replace(microsecond=0)
    rows = []
    for n in range(total):
        scope, customer = f'ops-{n % principals}', f'customer-{n % customers}'
        time = (occurred + timedelta(seconds=n // customers)).isoformat().replace('+00:00', 'Z')
        body = {**settings['event'], 'eventId': f'seed-{n}', 'customerId': customer,
                'instrumentId': 'card-' + customer, 'occurredAt': time}
        canonical = {**body, 'canonicalizationVersion': 1, 'scope': scope,
                     'amount': format(Decimal(body['amount']).normalize(), 'f')}
        document = json.dumps(canonical, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        digest = hashlib.sha256(('fraud-request-v1\n' + document).encode()).hexdigest()
        # JSON fixtures contain no tabs/newlines/backslashes; reject instead of relying on COPY escaping.
        check(not any(char in document for char in ('\t', '\n', '\\')), 'Unsupported COPY fixture character')
        rows.append('\t'.join((scope, body['eventId'], customer, body['instrumentId'], digest, time, document)))
    data = '\n'.join(rows)
    quote = lambda value: "'" + value.replace("'", "''") + "'"
    rule_values = ','.join('(' + quote(rule['ruleId']) + ',' + quote(rule['algorithmVersion']) + ')' for rule in stack.rules)
    template = r"""
    BEGIN;
    CREATE TEMP TABLE ops_seed (scope text,event_id text,customer_id text,instrument_id text,
        request_hash text,occurred_at timestamptz,input_json jsonb) ON COMMIT DROP;
    COPY ops_seed FROM STDIN;
    %s
    \.
    INSERT INTO fraud.customer_processing_state(scope,customer_id,last_accepted_at,customer_sequence)
      SELECT scope,customer_id,max(occurred_at),count(*) FROM ops_seed GROUP BY scope,customer_id;
    INSERT INTO fraud.instrument_owners SELECT DISTINCT scope,instrument_id,customer_id FROM ops_seed;
    INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,
      transaction_type,status,channel,amount,currency,category,occurred_at,accepted_at,customer_sequence,
      producer_id,source_permission_version,source_permissions,latitude,longitude,accuracy_km,location_source,city,input_json)
      SELECT scope,event_id,customer_id,instrument_id,request_hash,1,input_json->>'transactionType',input_json->>'status',
        input_json->>'channel',(input_json->>'amount')::numeric,input_json->>'currency',input_json->>'category',
        occurred_at,occurred_at,row_number() OVER(PARTITION BY scope,customer_id ORDER BY occurred_at,event_id),
        scope,1,'["VERIFIED_CARD_PRESENT_TERMINAL"]',
        (input_json#>>'{physicalLocation,latitude}')::numeric,(input_json#>>'{physicalLocation,longitude}')::numeric,
        (input_json#>>'{physicalLocation,accuracyKm}')::numeric,input_json#>>'{physicalLocation,source}',
        input_json#>>'{physicalLocation,city}',input_json-'scope'-'canonicalizationVersion' FROM ops_seed;
    INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,
      decision,coverage,evaluated_at,expected_rule_count)
      SELECT t.scope,t.event_id,gen_random_uuid(),p.policy_hash,'synthetic-history-fixture',t.customer_sequence-1,
        'CLEAR','COMPLETE',t.accepted_at,%d FROM fraud.transactions t CROSS JOIN fraud.policy_head p
        WHERE t.event_id LIKE 'seed-%%';
    INSERT INTO fraud.rule_results(scope,event_id,rule_id,algorithm_version,status,reason_code,evidence)
      SELECT e.scope,e.event_id,r.id,r.version,'NO_MATCH','SYNTHETIC_FIXTURE','{"evidenceSchemaVersion":1}'
      FROM fraud.evaluations e CROSS JOIN (VALUES %s) r(id,version) WHERE e.event_id LIKE 'seed-%%';
    COMMIT;
    VACUUM ANALYZE fraud.transactions;
    VACUUM ANALYZE fraud.evaluations;
    VACUUM ANALYZE fraud.rule_results;
    """
    stack.sql(textwrap.dedent(template) % (data, len(stack.rules), rule_values))
    return {'seedEvents': total, 'customers': customers, 'principals': principals,
            'fixtureSha256': hashlib.sha256(data.encode()).hexdigest(),
            'evaluatedThroughApi': False, 'invariants': invariant_counts(stack)}
