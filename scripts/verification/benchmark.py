"""One/two-instance, hot-customer, mixed and overload/recovery measurements."""
import json
import re
import threading
import time
from collections import Counter

import load
from recovery import event, invariant_counts
from stack import ROOT, check, command


class Sampler:
    def __init__(self, stack, services):
        self.stack, self.services = stack, services
        self.rows, self.errors = [], []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def collect(self):
        while not self.stop.is_set():
            try:
                values = self.stack.sql("""SELECT json_build_object('runtimeConnections',count(*),
                  'active',count(*) FILTER(WHERE state='active'), 'lockWaiters',count(*) FILTER(WHERE wait_event_type='Lock'),
                  'idleInTransaction',count(*) FILTER(WHERE state='idle in transaction')) FROM pg_stat_activity WHERE usename='fraud_runtime'""")
                stats = self.stack.compose('ps', '-q', *self.services).decode().split()
                # Docker reports observed CPU/RSS; these are samples, not allocator/pool internals.
                resources = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *stats]).decode().splitlines()
                self.rows.append({'elapsedSeconds': time.monotonic() - self.started,
                                  'database': json.loads(values),
                                  'containers': [json.loads(value) for value in resources]})
            except Exception as failure:
                self.errors.append(type(failure).__name__)
            self.stop.wait(self.stack.settings['sampleIntervalSeconds'])

    def __enter__(self):
        self.started = time.monotonic()
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(timeout=30)
        check(not self.thread.is_alive() and not self.errors and self.rows, 'Resource sampler failed or produced no samples')


def run(stack, record):
    settings = stack.settings
    service_text = (ROOT / 'config/service.example.yaml').read_text()
    pool_size = int(re.search(r'dbPoolMaxConnectionsPerReplica: (\d+)', service_text).group(1))
    check((2 + settings['rolloutSurge']) * pool_size <= settings['applicationConnectionBudget'], 'Replica/pool/surge budget exceeded')
    # The generator does not silently raise production/demo quotas to make a target pass.
    refill = int(re.search(r'ingest: \{capacity: \d+, refillPerSecond: (\d+)\}', service_text).group(1))
    check(settings['principals'] * refill >= settings['targetRequestsPerSecond'], 'Target exceeds configured principal quotas')
    expected_policy = stack.sql('SELECT policy_hash FROM fraud.policy_head WHERE singleton')
    sequence = 0
    accepted_events = 0

    def profile(name, replicas, rate, duration, hot=False, mixed=False, enforce=True):
        nonlocal sequence, accepted_events
        prefix = name + '-' + str(sequence)
        sequence += 1
        starts = invariant_counts(stack)
        services = ['app', 'peer'] if replicas == 2 else ['app']
        responses = Counter()
        guard = threading.Lock()
        failures = []
        ambiguous = []

        def request(index):
            principal = index % settings['principals']
            # One hot customer really means one scope/instrument, so one principal.
            if hot:
                principal = 0
            customer = 'hot-customer' if hot else f'customer-{index % settings["customers"]}'
            service = services[index % len(services)]
            if mixed and index % settings['readEvery'] == 0:
                status, body, _ = stack.request('/api/v1/evaluations?customerId=' + customer, principal=principal, service=service)
                write = False
            else:
                payload = event(settings, prefix + '-' + str(index), customer)
                status, body, _ = stack.request('/api/v1/transactions', payload, principal=principal, service=service)
                write = True
            with guard:
                responses[(write, status)] += 1
                if write and (status == 0 or status == 503 and json.loads(body).get('outcome') == 'UNKNOWN'):
                    ambiguous.append((principal, payload['eventId']))
                if status in (200, 201):
                    result = json.loads(body)
                    if write:
                        evaluation = result['evaluation']
                        if (status != 201 or evaluation['policyHash'] != expected_policy or len(evaluation['ruleResults']) != len(stack.rules)
                                or any(rule['status'] == 'DISABLED' for rule in evaluation['ruleResults'])):
                            failures.append('Unexpected policy/result/identity')
            return {'status': status}

        print(f'Workload {name}: {rate}/s for {duration}s on {replicas} instance(s)', flush=True)
        with Sampler(stack, services + ['db', 'redis']) as sampler:
            result = load.run(request, rate, duration, settings['maxInFlight'], settings['maxGeneratorLagMillis'],
                              lambda current, total: print(f'  {name}: offered {current}/{total}', flush=True))
            completed_at = time.monotonic()
        accepted = responses[(True, 201)]
        committed_ambiguous = 0
        if ambiguous:
            keys = ','.join(f"('ops-{principal}','{event_id}')" for principal, event_id in ambiguous)
            committed_ambiguous = int(stack.sql('SELECT count(*) FROM fraud.transactions WHERE (scope,event_id) IN (' + keys + ')'))
        after = invariant_counts(stack)
        check(after['transactions'] - starts['transactions'] == accepted + committed_ambiguous, name + ': HTTP/DB acceptance mismatch')
        check(not failures, name + ': ' + str(failures[:1]))
        accepted_events += accepted + committed_ambiguous
        passed = load.accepted(result, settings)
        result.update({'replicas': replicas, 'rate': rate, 'hotCustomer': hot, 'mixedReads': mixed,
                       'writesAccepted': accepted, 'ambiguousRequests': len(ambiguous), 'resolvedCommittedAmbiguous': committed_ambiguous, 'passedTarget': passed, 'invariants': after,
                       'sampling': sampler.rows, 'retries': 0, 'arrivalModel': 'open-loop evenly-spaced',
                       'poolMaxPerReplica': pool_size, 'applicationConnectionBudget': settings['applicationConnectionBudget']})
        record(name, result)
        check(load.within_error_budget(result, settings), name + ': unexpected HTTP/transport errors exceeded budget')
        if enforce:
            check(passed, name + ': target failed; inspect measured result, do not infer capacity')
        return result, completed_at

    profile('warmup-one', 1, settings['targetRequestsPerSecond'], settings['warmupSeconds'], enforce=False)
    profile('one-instance', 1, settings['targetRequestsPerSecond'], settings['durationSeconds'])
    stack.peer()
    profile('warmup-two', 2, settings['targetRequestsPerSecond'], settings['warmupSeconds'], enforce=False)
    profile('two-instances', 2, settings['targetRequestsPerSecond'], settings['durationSeconds'])
    profile('hot-customer', 2, settings['hotCustomerRequestsPerSecond'], settings['auxiliaryDurationSeconds'], hot=True)
    profile('read-write-mix', 2, settings['targetRequestsPerSecond'], settings['auxiliaryDurationSeconds'], mixed=True)
    overload, completed_at = profile('overload', 2, settings['overloadRequestsPerSecond'], settings['overloadSeconds'], enforce=False)
    # Measure from the end of overload arrivals, including outstanding response drain.
    began = completed_at - overload['drainSeconds']
    # Recover without resetting quota state, restarting or discarding pending work.
    recovered = False
    while time.monotonic() - began < settings['recoveryTimeoutSeconds']:
        result, completed_at = profile('recovery-' + str(sequence), 2, settings['targetRequestsPerSecond'], settings['warmupSeconds'], enforce=False)
        if result['passedTarget']:
            recovered = True
            break
    recovered_seconds = completed_at - began if recovered else None
    check(recovered and recovered_seconds <= settings['recoveryTimeoutSeconds'], 'Target did not recover within bound')
    record('overload-recovery', {'recoveredSeconds': recovered_seconds,
                                 'timingBoundary': 'end of overload arrival window to successful target-window HTTP completion', 'noRestartOrQuotaReset': True,
                                 'totalAcceptedMeasuredWrites': accepted_events, 'invariants': invariant_counts(stack)})
