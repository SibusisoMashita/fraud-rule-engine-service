"""Compare HTTP paths without changing policy, quotas or the running demo."""
import itertools
import json
import random
import re
import sys
from datetime import datetime, timezone

from balancing import BalancedStack
from commit_fault import build_image
from latency_client import payload
from recovery import invariant_counts
from stack import ROOT, check, command


def settings(path):
    values = json.loads(path.read_text())
    reference = json.loads((ROOT / 'config/latency.example.json').read_text())
    check(type(values) is dict, 'Latency configuration must be an object')
    check(values.keys() == reference.keys(), 'Latency configuration has missing/unknown keys')
    lists = {'replicas', 'origins', 'connections', 'workloads'}
    integers = values.keys() - lists - {'preallocateWorkers'}
    check(all(type(values[key]) is int and values[key] > 0 for key in integers),
          'Latency workload settings must be positive integers')
    check(type(values['preallocateWorkers']) is bool, 'preallocateWorkers must be boolean')
    for key, choices in (('origins', {'host', 'container'}), ('connections', {'fresh', 'reuse'}),
                         ('workloads', {'read', 'distinct', 'hot'})):
        items = values[key]
        check(type(items) is list and items and all(type(item) is str and item in choices for item in items)
              and len(items) == len(set(items)), 'Invalid ' + key)
    replicas = values['replicas']
    check(type(replicas) is list and replicas and all(type(n) is int and n > 0 for n in replicas)
          and len(replicas) == len(set(replicas)), 'Invalid replica counts')
    limits = (ROOT / 'config/service.example.yaml').read_text()
    pool = int(re.search(r'dbPoolMaxConnectionsPerReplica: (\d+)', limits).group(1))
    max_page = int(re.search(r'maxPageSize: (\d+)', limits).group(1))
    check(values['readPageSize'] <= max_page and values['seedEvents'] >= values['readPageSize'],
          'Seed enough records for a permitted full read page')
    check((max(replicas) + values['rolloutSurge']) * pool <= values['applicationConnectionBudget'],
          'Replica/surge pools exceed the configured connection budget')
    for workload in values['workloads']:
        route = 'read' if workload == 'read' else 'ingest'
        rate = values['readRequestsPerSecond' if workload == 'read' else 'writeRequestsPerSecond']
        refill = int(re.search(route + r': \{capacity: \d+, refillPerSecond: (\d+)', limits).group(1))
        check(rate <= values['principals'] * refill, 'Workload exceeds the configured ' + route + ' quota')
        check(rate * values['durationSeconds'] <= 100000, 'A diagnostic case may retain at most 100000 samples')
    check(values['connectTimeoutSeconds'] < values['requestTimeoutSeconds']
          and values['requestTimeoutSeconds'] > 31, 'Diagnostic timeout must allow the reported 31-second stall')
    check(values['slowRequestMillis'] < values['requestTimeoutSeconds'] * 1000,
          'Slow-request threshold must be below the diagnostic timeout')
    return values


class LatencyStack(BalancedStack):
    def __init__(self, output, verification, values, app_image=None):
        super().__init__(output, verification,
                         {'minReplicas': min(values['replicas']), 'maxReplicas': max(values['replicas']),
                          'drainSeconds': verification['faultBoundSeconds']}, app_image)
        # Only this diagnostic deployment exposes random, loopback-only direct app ports.
        self.config['services']['app']['ports'] = [self.port_definition(8080)]
        self.client_image = build_image()
        self.config['services']['latency-client'] = {
            'image': self.client_image, 'profiles': ['latency'], 'init': True,
            'user': '65534:65534', 'read_only': True, 'cap_drop': ['ALL'],
            'security_opt': ['no-new-privileges:true'], 'cpus': 1, 'mem_limit': '128m', 'pids_limit': 64,
            'volumes': [{'type': 'bind', 'source': str(ROOT / 'scripts/verification' / name),
                         'target': '/client/' + name, 'read_only': True}
                        for name in ('latency_client.py', 'load.py')],
            'command': ['python3', '-B', '/client/latency_client.py']}
        self.save()

    def provision(self):
        # Independent caller quotas, shared customer history: a hot customer is genuinely shared.
        super().provision(shared_scope='latency')

    def endpoints(self):
        containers = json.loads(command(['docker', 'inspect', *self.replicas()]))
        containers.sort(key=lambda row: int(row['Config']['Labels']['com.docker.compose.container-number']))
        direct = {'host': [], 'container': []}
        for row in containers:
            binding = row['NetworkSettings']['Ports']['8080/tcp']
            check(len(binding) == 1 and binding[0]['HostIp'] == '127.0.0.1', 'Direct port is not isolated to loopback')
            direct['host'].append('http://127.0.0.1:' + binding[0]['HostPort'])
            network = row['NetworkSettings']['Networks'][self.project + '_default']
            direct['container'].append('http://' + network['IPAddress'] + ':8080')
        return {'direct': direct,
                'balanced': {'host': [self.base()], 'container': ['http://load-balancer:8080']}}


def measure(stack, job, origin):
    data = json.dumps(job).encode()
    # Process/container startup is outside the client's per-request timing and offered window.
    timeout = job['durationSeconds'] + job['requestTimeoutSeconds'] + stack.settings['startupTimeoutSeconds']
    if origin == 'host':
        output = command([sys.executable, '-B', str(ROOT / 'scripts/verification/latency_client.py')],
                         data=data, timeout=timeout)
    else:
        output = stack.compose('run', '--rm', '--no-deps', '-T', 'latency-client', data=data, timeout=timeout)
    return json.loads(output)


def reconcile(stack, job, samples):
    counts = invariant_counts(stack)
    if job['workload'] == 'read':
        return counts
    # IDs and this SQL prefix are generated here, never taken from submitted values.
    prefix = job['runId']
    check(re.fullmatch(r'[a-z0-9-]+', prefix) is not None, 'Unsafe diagnostic ID')
    present = set(stack.sql("SELECT event_id FROM fraud.transactions WHERE scope='latency' AND event_id LIKE '"
                            + prefix + "-%'").splitlines())
    acknowledged = {prefix + '-' + str(row['index']) for row in samples if row['status'] == 201}
    uncertain = {prefix + '-' + str(row['index']) for row in samples if row['status'] in (0, 503)}
    check(acknowledged <= present, 'An acknowledged write is missing from storage')
    check(present <= acknowledged | uncertain, 'A rejected or unoffered write was committed')
    counts.update(acknowledged=len(acknowledged), uncertain=len(uncertain),
                  uncertainCommitted=len(present & uncertain))
    return counts


def run(stack, values, record):
    rng = random.Random(values['seed'])
    ordinal = 0
    slow_cases = []
    limited_cases = []
    event = {'transactionType': 'CARD_PURCHASE', 'status': 'BOOKED', 'channel': 'ECOMMERCE',
             'amount': '1.00', 'currency': 'ZAR', 'category': 'SYNTHETIC'}
    seed = {'event': event, 'workload': 'distinct', 'runId': 'latency-seed',
            'occurredAt': datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')}
    for index in range(values['seedEvents']):
        check(stack.request('/api/v1/transactions', payload(seed, index), principal=index % len(stack.keys))[0] == 201,
              'Could not seed a populated evaluation page through the API')
    record('latency-seed', {'events': values['seedEvents'], 'readPageSize': values['readPageSize'],
                           'invariants': invariant_counts(stack)})
    for replicas in sorted(values['replicas']):
        stack.scale(replicas)
        endpoints = stack.endpoints()
        # Warm the application separately; the measured clients retain their first requests.
        for base in endpoints['direct']['host']:
            stack.base_cached['warmup'] = base
            check(stack.request('/api/v1/evaluations?pageSize=' + str(values['readPageSize']), service='warmup')[0] == 200,
                  'Application warmup failed')
        for repetition in range(1, values['repetitions'] + 1):
            cases = list(itertools.product(('direct', 'balanced'), values['origins'],
                                           values['connections'], values['workloads']))
            rng.shuffle(cases)
            for route, origin, connection, workload in cases:
                ordinal += 1
                name = f'{ordinal:03d}-{replicas}app-{route}-{origin}-{connection}-{workload}-r{repetition}'
                print('Measuring ' + name, flush=True)
                job = {key: values[key] for key in ('durationSeconds', 'workers', 'maxGeneratorLagMillis',
                       'requestTimeoutSeconds', 'connectTimeoutSeconds', 'preallocateWorkers', 'slowRequestMillis', 'readPageSize')}
                job.update(baseUrls=endpoints[route][origin], keys=stack.keys, workload=workload,
                           connectionMode=connection, runId='latency-' + str(ordinal),
                           occurredAt=datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z'),
                           event=event,
                           policyHash=stack.sql('SELECT policy_hash FROM fraud.policy_head WHERE singleton'),
                           expectedRuleCount=len(stack.rules),
                           ratePerSecond=values['readRequestsPerSecond' if workload == 'read' else 'writeRequestsPerSecond'])
                before = invariant_counts(stack)
                measured = measure(stack, job, origin)
                samples = measured.pop('samples')
                sample_path = stack.output / ('latency-' + name + '.json')
                sample_path.write_text(json.dumps(samples, indent=2) + '\n')
                result = {**measured, 'replicas': replicas, 'route': route, 'origin': origin,
                          'connections': connection, 'workload': workload, 'repetition': repetition,
                          'endpoints': job['baseUrls'], 'samplesFile': sample_path.name,
                          'policyHash': job['policyHash'], 'retries': 0}
                # Retain the measurement even if reconciliation or a subsequent assertion fails.
                record(name, result)
                result['invariants'] = reconcile(stack, job, samples)
                record(name, result)
                if workload == 'read':
                    check(result['invariants'] == before, 'Read workload changed financial state')
                expected = 200 if workload == 'read' else 201
                check(samples and all(row['status'] == expected and not row.get('validationError') for row in samples),
                      name + ': HTTP/transport/response failure; inspect retained samples')
                if result['summary']['dropped']:
                    limited_cases.append(name)
                if workload != 'read':
                    first = samples[0]
                    payment = payload(job, first['index'])
                    status, original, _ = stack.request('/api/v1/transactions/' + payment['eventId'], principal=first['principalIndex'])
                    replay_status, replayed, _ = stack.request('/api/v1/transactions', payment, principal=first['principalIndex'])
                    check(status == replay_status == 200 and original == replayed, 'Same-ID retrieval/replay changed stored evidence')
                if result['summary']['slowRequests']:
                    slow_cases.append(name)
    record('latency-conclusion', {'slowCases': slow_cases, 'generatorLimitedCases': limited_cases,
           'thresholdMillis': values['slowRequestMillis'],
           'interpretation': 'Slow requests observed; cause requires isolation.' if slow_cases else
           ('Reported stalls not observed; generator-limited cases did not deliver the full offered workload.' if limited_cases else
            'Reported stalls not reproduced under these conditions; no root cause established.'),
           'originalGeneratorAvailable': False, 'productionCapacityEstablished': False})
    check(not slow_cases and not limited_cases,
          'Slow-request or generator-limit observations retained; do not claim the latency issue fixed')
