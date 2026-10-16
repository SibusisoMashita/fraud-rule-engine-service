#!/usr/bin/env python3
"""Disposable, measured Docker scaling demonstration. Ctrl-C removes owned resources."""
import argparse
import concurrent.futures
from collections import Counter
import hashlib
import json
import math
import os
import secrets
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / 'verification'))
from balancing import BalancedStack, ScalingDecision, settings
from load import run
from recovery import block_evaluations, event, invariant_counts, wait_for
from stack import ROOT, check, command


def probe(stack, path='/api/v1/evaluations', payload=None, principal=0, authenticated=True, headers=None):
    fields = {'Content-Type': 'application/json', **(headers or {})}
    if authenticated:
        fields['X-API-Key'] = stack.keys[principal]
    request = urllib.request.Request(stack.base_cached['app'] + path,
              data=json.dumps(payload).encode() if payload is not None else None, headers=fields)
    try:
        response = urllib.request.urlopen(request, timeout=stack.settings['requestTimeoutSeconds'])
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read(), {key.lower(): value for key, value in response.headers.items()}


def verify(stack, record):
    stack.scale(stack.scaling['maxReplicas'])
    routes = set()
    for i in range(stack.settings['principals'] * 3):
        status, _, headers = probe(stack, principal=i % stack.settings['principals'])
        check(status == 200, f'Proxied read failed: {status}; backend={headers.get("x-demo-backend")}')
        routes.add(headers['x-demo-backend'])
    check(len(routes) == stack.scaling['maxReplicas'], 'Requests did not reach all replicas')
    check(probe(stack, authenticated=False, headers={'X-Forwarded-For': '127.0.0.1', 'Forwarded': 'for=127.0.0.1'})[0] == 401, 'Forwarded headers bypassed authentication')
    stack.sql("UPDATE fraud.api_principals SET roles=ARRAY['READ'] WHERE principal_id='ops-1'")
    check(probe(stack, '/api/v1/rules', principal=1)[0] == 403, 'Role check bypassed')
    stack.sql("UPDATE fraud.api_principals SET roles=ARRAY['INGEST','READ','POLICY'] WHERE principal_id='ops-1'")
    payment = event(stack.settings, 'balanced-duplicate')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: probe(stack, '/api/v1/transactions', payment), range(2)))
    check(sorted(r[0] for r in results) == [200, 201] and results[0][1] == results[1][1], 'Duplicate was not one immutable effect')
    record('routing-auth-duplicate', {'backends': sorted(routes), 'statuses': sorted(r[0] for r in results), 'invariants': invariant_counts(stack)})
    # A fresh principal avoids earlier requests depleting independent per-app buckets.
    import re
    limit, refill = map(int, re.search(r'read: \{capacity: (\d+), refillPerSecond: (\d+)', (ROOT / 'config/service.example.yaml').read_text()).groups())
    secret = secrets.token_hex(32)
    principal = len(stack.keys)
    stack.keys.append('ops-quota.' + secret)
    digest = hashlib.sha256(secret.encode()).hexdigest()
    stack.sql("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) "
              "VALUES ('ops-quota','ops-quota',true,ARRAY['READ'],ARRAY[]::text[]); "
              "INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) "
              f"VALUES ('ops-quota','ops-quota','{digest}',clock_timestamp(),clock_timestamp()+interval '1 day');")
    offered = limit + max(1, limit // 2)
    began = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=stack.settings['principals']) as pool:
        quota_results = list(pool.map(lambda _: probe(stack, principal=principal), range(offered)))
    elapsed = time.monotonic() - began
    maximum = limit + math.ceil(elapsed * refill)
    statuses = Counter(result[0] for result in quota_results)
    backends = Counter(result[2].get('x-demo-backend', 'none') for result in quota_results)
    check(len(backends) == stack.scaling['maxReplicas'] and 'none' not in backends and max(backends.values()) <= limit,
          'Quota burst did not exercise both backends below their independent capacities')
    check(maximum < offered, 'Quota burst was too slow to distinguish shared and independent buckets')
    check(0 < statuses[200] <= maximum and statuses[429] > 0 and set(statuses) <= {200, 429}, 'Shared quota exceeded its global capacity/refill bound')
    record('shared-quota', {'statuses': dict(statuses), 'offered': offered, 'elapsedSeconds': elapsed,
                            'maximumAccepted': maximum, 'backendRequests': dict(backends)})
    # Real business activation, with both application container identities preserved.
    before = sorted(stack.replicas())
    candidate = stack.output / 'policy.yaml'
    candidate.write_text((ROOT / 'config/policy.example.yaml').read_text().replace('highAmount:\n    enabled: true', 'highAmount:\n    enabled: false'))
    for source, enabled in ((candidate, False), (ROOT / 'config/policy.example.yaml', True)):
        stack.compose('run', '--rm', '--no-deps', '-e', 'FRAUD_POLICY_COMMAND=activate', '-e', 'FRAUD_POLICY_INPUT=file:/policy/input.yaml', '-v', str(source) + ':/policy/input.yaml:ro', 'policy-seed')
        observed = set()
        for i in range(stack.settings['principals'] * 2):
            status, body, headers = probe(stack, '/api/v1/rules', principal=i % stack.settings['principals'])
            check(status == 200 and next(r for r in json.loads(body)['rules'] if r['ruleId'] == 'HIGH_AMOUNT')['enabled'] == enabled, 'Policy not live through proxy')
            observed.add(headers['x-demo-backend'])
        check(len(observed) == stack.scaling['maxReplicas'], 'Live policy not observed on all replicas')
    check(before == sorted(stack.replicas()), 'Policy activation restarted applications')
    check(probe(stack, '/api/v1/transactions', payment)[1] == results[0][1], 'Policy activation changed original replay')
    record('live-policy', {'replicasUnchanged': True})
    # One paused instance is removed; another continues serving. Then all alive apps become unready.
    victim = stack.replicas()[-1]
    command(['docker', 'pause', victim])
    try:
        stack.wait_ready(len(before) - 1)
        check(all(probe(stack, principal=i % stack.settings['principals'])[0] == 200 for i in range(stack.settings['principals'])), 'Unavailable instance still received traffic')
    finally:
        command(['docker', 'unpause', victim])
    stack.wait_ready(len(before))
    stack.compose('stop', 'redis')
    try:
        stack.wait_ready(0)
        began = time.monotonic()
        check(probe(stack)[0] == 503 and time.monotonic() - began < stack.settings['faultBoundSeconds'], 'No-ready-backend did not close ingress within bound')
        live = stack.compose('exec', '-T', 'app', 'curl', '--fail', '--silent', 'http://localhost:8080/health/live')
        check(json.loads(live)['status'] == 'UP', 'Test did not distinguish liveness/readiness')
    finally:
        stack.compose('start', 'redis')
    stack.wait_ready(len(before))
    record('readiness-recovery', {'unavailableRemoved': True, 'aliveButUnreadyExcluded': True, 'noReadyStatus': 503})
    check(probe(stack, '/api/v1/evaluations', payload={'padding': 'x' * int(re.search(r'maxBodyBytes: (\d+)', (ROOT / 'config/service.example.yaml').read_text()).group(1))})[0] == 413, 'Proxy body bound not enforced')
    import http.client
    from urllib.parse import urlsplit
    endpoint = urlsplit(stack.base_cached['app'])
    maximum = int(re.search(r'maxBodyBytes: (\d+)', (ROOT / 'config/service.example.yaml').read_text()).group(1))
    connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=stack.settings['requestTimeoutSeconds'])
    try:
        connection.request('POST', '/api/v1/transactions', body=iter([b'x' * (maximum + 1)]),
                           headers={'Content-Type': 'application/json', 'X-API-Key': stack.keys[0]}, encode_chunked=True)
        response = connection.getresponse()
        check(response.status == 413, 'Chunked request bypassed body bound')
        response.read()
    finally:
        connection.close()
    connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=stack.settings['requestTimeoutSeconds'])
    began = time.monotonic()
    try:
        connection.putrequest('POST', '/api/v1/transactions')
        connection.putheader('Content-Type', 'application/json')
        connection.putheader('Content-Length', '1')
        connection.endheaders()
        response = connection.getresponse()
        check(response.status == 408 and time.monotonic() - began < stack.settings['faultBoundSeconds'], 'Incomplete body was not bounded by proxy deadline')
        response.read()
    finally:
        connection.close()
    record('proxy-body-bounds', {'declared': 413, 'chunked': 413, 'incompleteBody': 408})
    # Hold real writes on both apps so scale-in must drain an admitted victim request.
    details = json.loads(command(['docker', 'inspect', *stack.replicas()]))
    details.sort(key=lambda item: int(item['Config']['Labels']['com.docker.compose.container-number']), reverse=True)
    victim = details[0]
    addresses = {network['IPAddress'] for network in victim['NetworkSettings']['Networks'].values()}
    lines = stack.runtime('show servers state fraud').splitlines()
    header = next(line for line in lines if line.startswith('#')).lstrip('# ').split()
    states = [dict(zip(header, line.split())) for line in lines if line and not line.startswith('#') and len(line.split()) > 1]
    targets = {row['srv_name'] for row in states if row['srv_addr'] in addresses}
    check(targets, 'Cannot identify the in-flight drain target')
    payments = [event(stack.settings, 'drain-' + str(i)) for i in range(len(details))]
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(payments) + 1) as pool:
        with block_evaluations(stack):
            writes = [pool.submit(probe, stack, '/api/v1/transactions', payment, i % stack.settings['principals'])
                      for i, payment in enumerate(payments)]
            wait_for(lambda: any(row['svname'] in targets and int(row['scur']) > 0 for row in stack.servers()),
                     stack.settings['faultBoundSeconds'], 'Victim did not receive an in-flight write')
            scaling = pool.submit(stack.scale, stack.scaling['minReplicas'])
            wait_for(lambda: any(row['svname'] in targets and row['status'].startswith('DRAIN') and int(row['scur']) > 0
                                 for row in stack.servers()),
                     stack.settings['faultBoundSeconds'], 'Victim was not draining an in-flight write')
            check(not scaling.done(), 'Scale-in completed before its admitted write drained')
        written = [future.result() for future in writes]
        scaling.result()
    check(all(result[0] == 201 for result in written), 'Scale-in interrupted an admitted payment')
    expected = {item['Id'] for item in details[len(details) - stack.scaling['minReplicas']:]}
    check(set(stack.replicas()) == expected, 'Scale-in removed the wrong container')
    for i, (payment, result) in enumerate(zip(payments, written)):
        check(probe(stack, '/api/v1/transactions', payment, principal=i % stack.settings['principals'])[:2] == (200, result[1]),
              'Drained payment replay changed its stored result')
    record('drained-scale-in', {'inFlightObserved': True, 'drainingObserved': True, 'backends': sorted(targets),
                               'completedWrites': len(written), 'invariants': invariant_counts(stack)})


def demonstrate(stack, record):
    v = stack.scaling
    decision = ScalingDecision(v)
    distribution = Counter()
    lock = threading.Lock()
    transitions = [v['minReplicas']]
    previous = None

    def traffic(index):
        status, _, headers = probe(stack, principal=index % stack.settings['principals'])
        with lock:
            distribution[headers.get('x-demo-backend', 'none')] += 1
        return {'status': status}

    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        for phase, duration in [('busy', v['busySeconds']), ('quiet', v['quietSeconds'])]:
            print('Traffic phase: ' + phase, flush=True)
            workload = pool.submit(run, traffic, v['busyRequestsPerSecond'], duration, stack.settings['maxInFlight'], stack.settings['maxGeneratorLagMillis']) if phase == 'busy' else None
            end = time.monotonic() + duration
            while time.monotonic() < end or workload is not None and not workload.done():
                rows = stack.rows()
                backend = next(r for r in rows if r['pxname'] == 'fraud' and r['svname'] == 'BACKEND')
                now, count = time.monotonic(), int(backend['hrsp_2xx'])
                replicas = len(stack.replicas())
                ready = sum(r['status'] == 'UP' for r in rows if r['pxname'] == 'fraud' and r['svname'].startswith('app'))
                rate = max(0, count - previous[1]) / (now - previous[0]) if previous and count >= previous[1] else None
                target = decision.decide(rate, replicas, ready, now)
                previous = (now, count)
                routes = ' '.join(r['svname'] + ':' + r['hrsp_2xx'] for r in rows if r['pxname'] == 'fraud' and r['svname'].startswith('app'))
                print(f'  successful requests/s={rate or 0:.1f} replicas={replicas} ready={ready} routes=[{routes}]', flush=True)
                if target != replicas:
                    print(f'  AUTOMATIC SCALE {replicas} -> {target}', flush=True)
                    stack.scale(target)
                    transitions.append(target)
                record('autoscaling', {'transitions': transitions[:], 'phase': phase, 'replicas': len(stack.replicas()), 'backendRequests': dict(distribution)})
                time.sleep(v['pollSeconds'])
            if workload:
                result = workload.result()
                check(result['dropped'] == 0 and set(result['statuses']) == {'200'}, 'Demo load failed; do not claim scaling success')
                record('busy-workload', result)
    check(len(transitions) >= 3 and max(transitions) == v['maxReplicas'] and transitions[-1] == v['minReplicas'], 'Automatic scale-out/scale-in not observed')
    check(len(distribution) == v['maxReplicas'] and 'none' not in distribution, 'Load was not distributed')
    print('Observed automatic transitions: ' + ' -> '.join(map(str, transitions)) + '\nRequests served by each backend: ' + str(dict(distribution)), flush=True)
    record('final', {'transitions': transitions, 'backendRequests': dict(distribution), 'invariants': invariant_counts(stack)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true', help='Also exercise authorization, quotas, duplicates, policy, failures and draining')
    parser.add_argument('--config', type=Path, default=ROOT / 'config/scaling-demo.example.json')
    args = parser.parse_args()
    os.chdir(ROOT)
    v = settings(args.config)
    verification = json.loads((ROOT / 'config/verification.example.json').read_text())
    output = ROOT / 'target/scaling' / (time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + str(os.getpid()))
    output.mkdir(parents=True, mode=0o700)
    report = {'passed': False, 'settings': v, 'checks': {}}
    stack = None
    def save(): (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    def record(name, result):
        report['checks'][name] = result
        save()
    def stop(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        stack = BalancedStack(output, verification, v)
        stack.start()
        print('Docker project: ' + stack.project + '\nSingle entrypoint: ' + stack.base_cached['app'] + '\nWatch app replicas in Docker Desktop. Ctrl-C cleans up this project.', flush=True)
        report['environment'] = stack.manifest()
        save()
        if args.verify: verify(stack, record)
        demonstrate(stack, record)
        report['passed'] = True
    except BaseException as failure:
        report['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        if stack:
            try: (output / 'failure.log').write_text(stack.failure_logs())
            except Exception: pass
        raise
    finally:
        try:
            if stack:
                stack.close()
            else:
                private = output / 'private'
                if private.exists():
                    for path in private.iterdir():
                        path.unlink()
                    private.rmdir()
        except Exception as failure:
            report['passed'] = False
            report['cleanupFailure'] = type(failure).__name__
            raise
        finally: save()
        print('Evidence: ' + str(output / 'results.json'), flush=True)


if __name__ == '__main__': main()
