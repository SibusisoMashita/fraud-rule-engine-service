import contextlib
import json
import socket
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from latency_client import measure, payload, quantiles
from load import run


class RecordingServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, **settings):
        super().__init__(('127.0.0.1', 0), RecordingHandler)
        self.settings, self.requests, self.connections = settings, [], 0
        self.guard = threading.Lock()

    def get_request(self):
        result = super().get_request()
        with self.guard:
            self.connections += 1
        return result


class RecordingHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def respond(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        with self.server.guard:
            self.server.requests.append((self.command, self.path, body, self.headers.get('X-API-Key')))
        settings = self.server.settings
        time.sleep(settings.get('delay', 0))
        response = {'items': [{}] * settings.get('items', 20)} if self.command == 'GET' else {
            'evaluation': {'policyHash': settings.get('hash', 'test-policy'), 'ruleResults': [{}, {}]}}
        if settings.get('echo'):
            response = {'secret': self.headers['X-API-Key']}
        encoded = json.dumps(response).encode()
        try:
            self.send_response(settings.get('status', 200 if self.command == 'GET' else 201))
            self.send_header('Content-Length', str(len(encoded) + settings.get('missingBytes', 0)))
            self.send_header('Content-Type', 'application/json')
            self.send_header('X-Correlation-ID', '12345678-1234-1234-1234-123456789abc')
            self.send_header('X-Demo-Backend', 'app2')
            self.send_header('Retry-After', '2')
            if settings.get('close'):
                self.send_header('Connection', 'close')
            self.end_headers()
            time.sleep(settings.get('bodyDelay', 0))
            self.wfile.write(encoded)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


@contextlib.contextmanager
def serving(**settings):
    server = RecordingServer(**settings)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)


def job(server, **changes):
    return {'baseUrls': ['http://127.0.0.1:' + str(server.server_port)],
            'keys': ['generated-secret-one', 'generated-secret-two'],
            'workload': 'distinct', 'readPageSize': 20, 'ratePerSecond': 20, 'durationSeconds': .2,
            'workers': 1, 'maxGeneratorLagMillis': 1000,
            'requestTimeoutSeconds': 1, 'connectTimeoutSeconds': .2,
            'connectionMode': 'reuse', 'preallocateWorkers': True,
            'runId': 'unit-run', 'event': {'amount': '1.00', 'currency': 'ZAR', 'category': 'SYNTHETIC'},
            'occurredAt': '2026-11-02T00:00:00Z', 'policyHash': 'test-policy',
            'expectedRuleCount': 2, 'slowRequestMillis': 100, **changes}


class LatencyTransportTest(unittest.TestCase):
    def test_real_keepalive_reuses_one_connection_and_marks_only_the_first_request(self):
        with serving() as server:
            result = measure(job(server))
            self.assertEqual(server.connections, 1)
            self.assertEqual(len(server.requests), 4)
        self.assertEqual(result['summary']['connectionOpens'], 1)
        self.assertEqual([row['firstRequestOnConnection'] for row in result['samples']],
                         [True, False, False, False])
        self.assertEqual([row['firstRequestOnWorker'] for row in result['samples']],
                         [True, False, False, False])
        self.assertEqual(result['summary']['validationErrors'], {})
        self.assertEqual(result['summary']['firstRequest']['count'], 1)
        self.assertEqual(result['summary']['subsequentRequest']['count'], 3)
        for index, sample in enumerate(result['samples']):
            self.assertEqual(sample['status'], 201)
            self.assertEqual(sample['principalIndex'], index % 2)
            self.assertEqual(sample['backend'], 'app2')
            self.assertEqual(sample['retryAfter'], '2')
            self.assertIsNotNone(sample['correlationId'])
            for field in ('dnsMillis', 'connectMillis', 'sendMillis', 'ttfbMillis',
                          'responseHeadersMillis', 'bodyMillis', 'schedulerDelayMillis',
                          'responseMillis', 'validationMillis'):
                self.assertGreaterEqual(sample[field], 0)
            self.assertGreaterEqual(sample['latencyMillis'], sample['totalMillis'])
            if index:
                self.assertEqual(sample['dnsMillis'], 0)
                self.assertEqual(sample['connectMillis'], 0)

    def test_fast_reads_exercise_all_fixed_clients_with_lazy_or_preallocated_threads(self):
        for preallocate in (False, True):
            with self.subTest(preallocate=preallocate), serving() as server:
                result = measure(job(server, workload='read', workers=10, ratePerSecond=100,
                                     durationSeconds=.3, preallocateWorkers=preallocate))
                self.assertEqual(server.connections, 10)
            self.assertEqual(result['summary']['dropped'], 0)
            self.assertEqual(result['client']['workersUsed'], 10)
            self.assertEqual(result['summary']['firstRequest']['count'], 10)
            self.assertEqual(result['summary']['newConnection']['count'], 10)
            self.assertEqual({sample['worker'] for sample in result['samples']}, set(range(10)))
            self.assertTrue(all(sample['worker'] == sample['index'] % 10 for sample in result['samples']))

    def test_fresh_mode_really_opens_one_tcp_connection_per_request(self):
        with serving() as server:
            result = measure(job(server, connectionMode='fresh'))
            self.assertEqual(server.connections, 4)
        self.assertEqual(result['summary']['connectionOpens'], 4)
        self.assertTrue(all(row['firstRequestOnConnection'] for row in result['samples']))
        self.assertEqual(result['summary']['firstRequest']['count'], 1)
        self.assertEqual(result['summary']['newConnection']['count'], 4)

    def test_server_close_is_measured_as_a_new_connection_without_retrying(self):
        with serving(close=True) as server:
            result = measure(job(server))
            self.assertEqual(server.connections, 4)
        self.assertTrue(all(row['firstRequestOnConnection'] for row in result['samples']))
        self.assertTrue(all(row['ttfbMillis'] is not None for row in result['samples']))
        self.assertEqual(result['summary']['retries'], 0)

    def test_read_workload_uses_get_and_records_denials_with_all_request_timings(self):
        with serving(status=429, delay=.015) as server:
            result = measure(job(server, workload='read', slowRequestMillis=1))
            self.assertTrue(all(request[0] == 'GET' and not request[2] for request in server.requests))
        self.assertEqual(result['summary']['denied'], 4)
        self.assertEqual(result['summary']['slowRequests'], 4)
        self.assertEqual(result['summary']['statuses'], {'429': 4})
        self.assertGreaterEqual(result['summary']['timings']['ttfbMillis']['p50'], 10)
        self.assertIsNone(result['summary']['successfulP95Millis'])

    def test_read_workload_checks_the_configured_populated_page_size(self):
        with serving() as server:
            result = measure(job(server, workload='read'))
            self.assertTrue(all(request[1] == '/api/v1/evaluations?pageSize=20' for request in server.requests))
        self.assertEqual(result['summary']['validationErrors'], {})
        with serving(items=0) as server:
            result = measure(job(server, workload='read'))
        self.assertEqual(result['summary']['validationErrors'], {'INVALID_READ_RESPONSE': 4})

    def test_timeout_does_not_retry_or_replace_the_uncertain_write(self):
        with serving(delay=.1) as server:
            settings = job(server, ratePerSecond=100, durationSeconds=.01, requestTimeoutSeconds=.025)
            result = measure(settings)
            self.assertEqual(len(server.requests), 1)
            self.assertEqual(json.loads(server.requests[0][2]), payload(settings, 0))
        sample = result['samples'][0]
        self.assertEqual(sample['status'], 0)
        self.assertEqual(sample['transportError'], 'TimeoutError')
        self.assertEqual(sample['eventId'], 'unit-run-0')
        self.assertEqual(result['summary']['retries'], 0)
        self.assertGreaterEqual(sample['totalMillis'], 20)

    def test_failed_success_validation_is_reported_without_response_contents(self):
        with serving(hash='different-policy') as server:
            result = measure(job(server))
        self.assertEqual(result['summary']['validationErrors'], {'POLICY_HASH_MISMATCH': 4})
        self.assertNotIn('different-policy', json.dumps(result))

    def test_incomplete_body_keeps_headers_without_claiming_success_or_retrying(self):
        for settings, failure in (({'missingBytes': 5, 'close': True}, 'IncompleteRead'),
                                  ({'bodyDelay': .1}, 'TimeoutError')):
            with self.subTest(failure=failure), serving(**settings) as server:
                result = measure(job(server, ratePerSecond=100, durationSeconds=.01,
                                     requestTimeoutSeconds=.025))
                self.assertEqual(len(server.requests), 1)
            sample = result['samples'][0]
            self.assertEqual(sample['status'], 0)
            self.assertEqual(sample['responseStatus'], 201)
            self.assertEqual(sample['transportError'], failure)
            self.assertEqual(sample['backend'], 'app2')
            self.assertIsNotNone(sample['correlationId'])
            self.assertIsNotNone(sample['bodyMillis'])
            self.assertIsNotNone(sample['ttfbMillis'])
            self.assertEqual(result['summary']['retries'], 0)

    def test_dns_failure_keeps_the_phase_timing_and_never_prints_exception_details(self):
        with serving() as server:
            settings = job(server, ratePerSecond=100, durationSeconds=.01)
        with patch('latency_client.socket.getaddrinfo', side_effect=socket.gaierror('private-key-value')):
            result = measure(settings)
        sample = result['samples'][0]
        self.assertEqual(sample['status'], 0)
        self.assertEqual(sample['transportError'], 'gaierror')
        self.assertEqual(sample['connectionOpens'], 0)
        self.assertGreaterEqual(sample['dnsMillis'], 0)
        self.assertEqual(sample['connectMillis'], 0)
        self.assertNotIn('private-key-value', json.dumps(result))

    def test_cli_output_contains_neither_job_secrets_nor_echoed_response_bodies(self):
        with serving(echo=True) as server:
            settings = job(server)
            settings['event']['category'] = 'private-payload-value'
            completed = subprocess.run([sys.executable, str(Path(__file__).with_name('latency_client.py'))],
                                       input=json.dumps(settings), capture_output=True, text=True, timeout=5)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        result = json.loads(completed.stdout)
        self.assertEqual(result['summary']['validationErrors'], {'INVALID_SUCCESS_RESPONSE': 4})
        for secret in settings['keys'] + ['private-payload-value']:
            self.assertNotIn(secret, completed.stdout + completed.stderr)
        self.assertEqual(completed.stderr, '')
        settings['baseUrls'] = ['https://private-user:private-password@localhost']
        completed = subprocess.run([sys.executable, str(Path(__file__).with_name('latency_client.py'))],
                                   input=json.dumps(settings), capture_output=True, text=True, timeout=5)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(json.loads(completed.stdout), {'error': 'ValueError'})
        self.assertNotIn('private-password', completed.stdout + completed.stderr)

    def test_payload_is_stable_across_reconciliation_and_hot_customers_share_identity(self):
        with serving() as server:
            settings = job(server)
        first = payload(settings, 0)
        self.assertEqual(first, payload(settings, 0))
        self.assertNotEqual(first['customerId'], payload(settings, 1)['customerId'])
        hot = {**settings, 'workload': 'hot'}
        self.assertEqual(payload(hot, 0)['customerId'], payload(hot, 1)['customerId'])
        self.assertEqual(payload(hot, 0)['instrumentId'], payload(hot, 1)['instrumentId'])
        self.assertNotEqual(payload(hot, 0)['eventId'], payload(hot, 1)['eventId'])
        self.assertEqual(settings['event'], {'amount': '1.00', 'currency': 'ZAR', 'category': 'SYNTHETIC'})

    def test_quantiles_use_nearest_rank_and_expose_tail_and_maximum(self):
        result = quantiles(list(range(1, 1001)))
        self.assertEqual(result, {'p50': 500, 'p95': 950, 'p99': 990, 'p99.9': 999, 'max': 1000})
        self.assertTrue(all(value is None for value in quantiles([]).values()))

    def test_retained_samples_keep_scheduled_arrival_and_preallocate_every_worker(self):
        observed = set()
        guard = threading.Lock()

        def request(index, scheduled):
            with guard:
                observed.add(threading.get_ident())
            self.assertLessEqual(scheduled, time.monotonic())
            time.sleep(.03)
            return {'status': 201, 'index': index}

        result = run(request, 100, .08, 4, 1000, with_schedule=True,
                     include_samples=True, preallocate_workers=True)
        self.assertEqual(len(observed), 4)
        self.assertEqual(result['offered'], result['sent'] + result['dropped'])
        self.assertEqual(len(result['samples']), result['sent'])
        self.assertTrue(all(row['latencyMillis'] >= 30 for row in result['samples']))

    def test_fixed_client_drops_when_busy_instead_of_queueing_on_an_idle_client(self):
        threads = {0: set(), 1: set()}

        def request(index):
            threads[index % 2].add(threading.get_ident())
            time.sleep(.08 if index % 2 == 0 else .001)
            return {'status': 201, 'index': index}

        result = run(request, 100, .12, 2, 1000, include_samples=True, fixed_workers=True)
        indices = {sample['index'] for sample in result['samples']}
        self.assertGreater(result['dropped'], 0)
        self.assertNotIn(2, indices)
        self.assertTrue(set(range(1, 12, 2)).issubset(indices))
        self.assertEqual(result['sent'] + result['dropped'], result['offered'])
        self.assertEqual([len(values) for values in threads.values()], [1, 1])
        self.assertNotEqual(threads[0], threads[1])

    def test_response_timing_excludes_validation_and_slow_count_uses_scheduled_latency(self):
        from latency_client import response_validation

        def slow_validation(*args):
            time.sleep(.025)
            return response_validation(*args)

        with serving() as server, patch('latency_client.response_validation', side_effect=slow_validation):
            result = measure(job(server, ratePerSecond=10, durationSeconds=.1, slowRequestMillis=20))
        sample = result['samples'][0]
        self.assertGreaterEqual(sample['validationMillis'], 20)
        self.assertGreaterEqual(sample['totalMillis'] - sample['responseMillis'], 20)
        self.assertEqual(result['summary']['slowRequests'], 1)
        self.assertTrue(sample['slow'])
        self.assertIn('validation', result['client']['latencyBoundary'])


if __name__ == '__main__':
    unittest.main()
