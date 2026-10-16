import socket
import os
import subprocess
import hashlib
import tempfile
import zipfile
from pathlib import Path
from inventory import verify
import struct
import threading
import time
import unittest
from unittest.mock import patch

from faults import CommitProxy, receive
from stack import ROOT, Stack, command
from subprocess import CompletedProcess
from load import accepted, percentile, run, summarize, within_error_budget


class WorkloadTest(unittest.TestCase):
    def test_generator_drops_arrivals_when_bounded_inflight_is_full(self):
        def slow(_):
            time.sleep(.06)
            return {'status': 201}
        result = run(slow, rate=100, seconds=.15, workers=1, max_lag_ms=100)
        self.assertEqual(result['offered'], 15)
        self.assertGreater(result['dropped'], 0)
        self.assertEqual(result['offered'], result['sent'] + result['dropped'])
        self.assertGreaterEqual(result['p95Millis'], 60)

    def test_fast_responses_do_not_shorten_the_offered_window(self):
        result = run(lambda _: {'status': 201}, rate=10, seconds=.2, workers=1, max_lag_ms=100)
        self.assertEqual(result['sent'], 2)
        self.assertGreaterEqual(result['observedSeconds'], .2)
        self.assertLessEqual(result['successfulRequestsPerSecond'], 10)

    def test_overload_denials_are_expected_but_unexpected_errors_still_fail(self):
        settings = {'maxUnexpectedErrorFraction': .001}
        denied = summarize([{'status': 429, 'latencyMillis': 10}], 1, 1, 0)
        self.assertTrue(within_error_budget(denied, settings))
        for status in (0, 401, 500):
            failed = summarize([{'status': status, 'latencyMillis': 10}], 1, 1, 0)
            self.assertFalse(within_error_budget(failed, settings))

    def test_latency_and_target_failure_include_denied_and_transport_failed_requests(self):
        settings = {'p95Millis': 300, 'p99Millis': 1000, 'maxUnexpectedErrorFraction': .001}
        result = summarize([{'status': 201, 'latencyMillis': 10}, {'status': 503, 'latencyMillis': 800}], 2, 1, 0)
        self.assertEqual(result['p95Millis'], 800)
        self.assertEqual(result['successfulP95Millis'], 10)
        self.assertFalse(accepted(result, settings))
        failed = summarize([{'status': 0, 'latencyMillis': 5}], 1, 1, 0)
        self.assertFalse(accepted(failed, settings))
        self.assertIsNone(percentile([], .95))
        self.assertEqual(percentile([30, 10, 20], .95), 30)


class WireFaultTest(unittest.TestCase):
    def test_upstream_timeout_closes_the_accepted_client(self):
        proxy = CommitProxy(('127.0.0.1', 1))
        try:
            # Connect directly so only the proxy's upstream attempt is replaced.
            with patch('faults.socket.create_connection', side_effect=socket.timeout()):
                with socket.socket() as client:
                    client.settimeout(2)
                    client.connect(('127.0.0.1', proxy.port))
                    self.assertEqual(client.recv(1), b'')
        finally:
            proxy.close()

    def test_fragmented_frames_forward_until_a_commit_ack_is_dropped(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen()
        done = threading.Event()
        errors = []
        proxy = CommitProxy(listener.getsockname())
        def backend():
            try:
                with listener.accept()[0] as server:
                    ready = b'Z' + struct.pack('!I', 5) + b'I'
                    for byte in ready:
                        server.sendall(bytes([byte]))
                    done.wait(2)
                    server.sendall(b'C' + struct.pack('!I', 11) + b'COMMIT\0')
                    time.sleep(.02)
            except Exception as failure:
                errors.append(failure)
        worker = threading.Thread(target=backend)
        worker.start()
        try:
            with socket.create_connection(('127.0.0.1', proxy.port), timeout=2) as client:
                self.assertEqual(receive(client, 6), b'Z\0\0\0\5I')
                proxy.armed.set()
                done.set()
                self.assertEqual(client.recv(1), b'')
                self.assertTrue(proxy.dropped.is_set())
        finally:
            done.set()
            proxy.close()
            listener.close()
            worker.join(3)
        self.assertFalse(errors)
        self.assertFalse(worker.is_alive())

    def test_one_armed_fault_drops_exactly_one_of_two_concurrent_commits(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen()
        listener.settimeout(3)
        proxy = CommitProxy(listener.getsockname())
        barrier = threading.Barrier(3)
        errors, responses = [], []
        frame = b'C' + struct.pack('!I', 11) + b'COMMIT\0'

        def backend():
            try:
                with listener.accept()[0] as connection:
                    barrier.wait(3)
                    connection.sendall(frame)
                    connection.recv(1)
            except Exception as failure:
                errors.append(type(failure).__name__)

        workers = [threading.Thread(target=backend) for _ in range(2)]
        clients = []
        try:
            for worker in workers:
                worker.start()
            clients = [socket.create_connection(('127.0.0.1', proxy.port), timeout=3) for _ in range(2)]
            proxy.armed.set()
            barrier.wait(3)
            for client in clients:
                response = client.recv(len(frame))
                if response:
                    response += receive(client, len(frame) - len(response))
                responses.append(response)
            self.assertEqual(sorted(responses), [b'', frame])
            self.assertTrue(proxy.dropped.is_set())
            self.assertFalse(proxy.armed.is_set())
        finally:
            proxy.close()
            listener.close()
            for client in clients:
                client.close()
            for worker in workers:
                worker.join(4)
        self.assertFalse(errors)
        self.assertTrue(all(not worker.is_alive() for worker in workers))


class DatabaseGuardTest(unittest.TestCase):
    def test_nonempty_partial_and_wrong_family_directories_are_rejected_before_startup(self):
        with tempfile.TemporaryDirectory() as folder:
            data = Path(folder)
            partial = data / 'partial-cluster'
            partial.write_bytes(b'preserve-this-data')
            for version, marker in ((None, None), ('17', 'different-image-family')):
                if version is not None:
                    (data / 'PG_VERSION').write_text(version)
                    (data / '.fraud-runtime-family').write_text(marker)
                result = subprocess.run(['sh', str(ROOT / 'docker/postgres-entrypoint.sh'), 'postgres'],
                                        env=dict(os.environ, PGDATA=folder, FRAUD_DATABASE_FAMILY='expected-family'),
                                        capture_output=True)
                self.assertEqual(result.returncode, 1)
                self.assertIn(b'Unrecognized PostgreSQL data directory', result.stderr)
                self.assertEqual(partial.read_bytes(), b'preserve-this-data')


class DiagnosticTest(unittest.TestCase):
    def test_stdin_credentials_echoed_by_a_failed_command_are_not_reported(self):
        secret = 'generated-test-password'
        result = CompletedProcess(['docker', 'compose'], 1, stdout=b'',
                                  stderr=f"ERROR: CREATE ROLE example PASSWORD '{secret}'".encode())
        with patch('stack.subprocess.run', return_value=result):
            with self.assertRaises(RuntimeError) as caught:
                command(['docker', 'compose'], data=secret.encode())
        self.assertNotIn(secret, str(caught.exception))
        self.assertIn('diagnostics withheld', str(caught.exception))

    def test_retained_failure_logs_redact_credentials_and_key_secret_components(self):
        with tempfile.TemporaryDirectory() as folder:
            private = Path(folder)
            (private / 'db-password').write_text('database-secret-value\n')
            (private / 'empty').write_text('')
            (private / 'local-api-key').write_text('local.demo-secret-value\n')
            stack = Stack.__new__(Stack)
            stack.credentials = private
            stack.keys = ['ops-0.api-secret-value']
            stack.compose = lambda *_: (b'Connection refused; database-secret-value; '
                                       b'ops-0.api-secret-value; api-secret-value; '
                                       b'local.demo-secret-value; demo-secret-value')
            logs = stack.failure_logs()
            self.assertIn('Connection refused', logs)
            self.assertNotIn('database-secret-value', logs)
            self.assertNotIn('api-secret-value', logs)
            self.assertNotIn('demo-secret-value', logs)
            self.assertEqual(logs.count('[REDACTED]'), 5)


class InventoryTest(unittest.TestCase):
    def test_inventory_rejects_an_unlisted_packaged_dependency(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / 'application.jar'
            content = b'independent-library-fixture'
            bom = {'components': [{'hashes': [{'alg': 'SHA-256', 'content': hashlib.sha256(content).hexdigest()}]}]}
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/lib/known.jar', content)
            self.assertEqual(verify(bom, jar)['matchedSha256'], 1)
            with zipfile.ZipFile(jar, 'a') as archive:
                archive.writestr('BOOT-INF/lib/unlisted.jar', b'different-library')
            with self.assertRaisesRegex(AssertionError, 'unlisted.jar'):
                verify(bom, jar)

    def test_empty_inventory_is_not_coverage(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / 'empty.jar'
            with zipfile.ZipFile(jar, 'w'):
                pass
            with self.assertRaises(AssertionError):
                verify({'components': []}, jar)


if __name__ == '__main__':
    unittest.main()
