"""Diagnostic ownership, evidence retention and reconciliation; no Docker daemon needed."""
import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import latency
from stack import ROOT


def example():
    return json.loads((ROOT / 'config/latency.example.json').read_text())


def verification():
    return json.loads((ROOT / 'config/verification.example.json').read_text())


class SettingsTest(unittest.TestCase):
    def load(self, values):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'latency.json'
            path.write_text(json.dumps(values))
            return latency.settings(path)

    def test_example_and_exact_configured_quota_and_pool_boundaries_are_valid(self):
        values = example()
        self.assertEqual(self.load(values), values)
        values.update(principals=1, replicas=[1, 4], applicationConnectionBudget=50,
                      workloads=['read', 'distinct', 'hot'], readRequestsPerSecond=10,
                      writeRequestsPerSecond=5)
        self.assertEqual(self.load(values), values)

    def test_configuration_errors_fail_before_starting_any_deployment(self):
        cases = [
            {'extra': 1}, {'workers': True}, {'workers': 1.5}, {'principals': 0},
            {'preallocateWorkers': 1}, {'replicas': '1,4'}, {'replicas': []},
            {'replicas': [True]}, {'replicas': [1, 1]}, {'replicas': [0]},
            {'origins': 'host'}, {'origins': []}, {'origins': ['host', 'host']},
            {'origins': ['remote']}, {'connections': ['reuse', 'reuse']},
            {'connections': [False]}, {'workloads': ['delete']},
            {'replicas': [1, 5]}, {'requestTimeoutSeconds': 31},
            {'connectTimeoutSeconds': 45}, {'slowRequestMillis': 45000},
            {'readPageSize': 101}, {'readPageSize': 20, 'seedEvents': 19},
            {'principals': 1, 'readRequestsPerSecond': 11},
            {'principals': 1, 'workloads': ['distinct'], 'writeRequestsPerSecond': 6},
            {'durationSeconds': 5001},
        ]
        for change in cases:
            with self.subTest(change=change), self.assertRaises(AssertionError):
                self.load({**example(), **change})
        incomplete = example()
        incomplete.pop('workers')
        with self.assertRaises(AssertionError):
            self.load(incomplete)
        with self.assertRaises(AssertionError):
            self.load([])


class IsolatedStackTest(unittest.TestCase):
    @staticmethod
    def compose_fixture():
        app = {'image': 'demo:local', 'build': '.', 'environment': {},
               'ports': ['127.0.0.1:8080:8080']}
        return {'name': 'existing-demo', 'services': {
                    'app': app, 'migrate': copy.deepcopy(app),
                    'db': {'image': 'postgres:pinned', 'ports': ['5432:5432'],
                           'volumes': [{'type': 'volume', 'source': 'postgres-data',
                                        'target': '/var/lib/postgresql/data'}]}},
                'networks': {'default': {'name': 'existing-demo_default'}},
                'volumes': {'postgres-data': {'name': 'existing-demo_postgres-data'}},
                'secrets': {'db-admin-password': {'file': '/existing/secrets/db-password'},
                            'local-api-key': {'file': '/existing/secrets/api-key'}}}

    def test_projects_ports_volumes_credentials_and_images_are_isolated(self):
        overlay = {'services': {'load-balancer': {'image': 'haproxy:pinned', 'environment': {}}}}

        def docker(args, *_, **__):
            if args[:3] == ['docker', 'image', 'inspect']:
                self.assertEqual(args[-1], 'review:latency')
                return b'[{"Id":"sha256:reviewed-app"}]'
            self.assertEqual(args, ['docker', 'compose', 'config', '--format', 'json'])
            return json.dumps(self.compose_fixture()).encode()

        with tempfile.TemporaryDirectory() as directory, \
                patch('stack.command', side_effect=docker), \
                patch('balancing.command', return_value=json.dumps(overlay).encode()), \
                patch('latency.build_image', return_value='sha256:reviewed-client'):
            owned = []
            for name in ('first', 'second'):
                output = Path(directory) / name
                output.mkdir()
                owned.append(latency.LatencyStack(output, verification(), example(), 'review:latency'))
            first, second = owned
            self.assertNotEqual(first.project, second.project)
            self.assertNotEqual(first.credentials, second.credentials)
            for stack in owned:
                config = json.loads(stack.file.read_text())
                for section in ('networks', 'volumes'):
                    for resource in config[section].values():
                        self.assertTrue(resource['name'].startswith(stack.project + '_'))
                        self.assertFalse(resource.get('external'))
                for name in ('app', 'migrate', 'peer', 'restore-app'):
                    self.assertEqual(config['services'][name]['image'], 'sha256:reviewed-app')
                    self.assertNotIn('build', config['services'][name])
                for name in ('app', 'load-balancer'):
                    ports = config['services'][name]['ports']
                    self.assertEqual(len(ports), 1)
                    self.assertEqual((ports[0]['published'], ports[0]['host_ip']), ('0', '127.0.0.1'))
                for name, secret in config['secrets'].items():
                    path = Path(secret['file'])
                    self.assertEqual(path.parent, stack.credentials)
                    if stack is first:
                        self.assertNotEqual(path.read_text(), Path(second.config['secrets'][name]['file']).read_text())
                client = config['services']['latency-client']
                self.assertEqual(client['image'], 'sha256:reviewed-client')
                self.assertEqual(client['user'], '65534:65534')
                self.assertTrue(client['read_only'])
                self.assertNotIn('secrets', client)
                self.assertNotIn('environment', client)
                self.assertEqual({Path(v['source']).name for v in client['volumes']},
                                 {'latency_client.py', 'load.py'})
                self.assertTrue(all(v['read_only'] for v in client['volumes']))

    def test_direct_addresses_follow_replica_numbers_and_reject_public_bindings(self):
        stack = latency.LatencyStack.__new__(latency.LatencyStack)
        stack.project = 'owned'
        stack.replicas = Mock(return_value=['second', 'first'])
        stack.base = Mock(return_value='http://127.0.0.1:43000')
        containers = [
            {'Config': {'Labels': {'com.docker.compose.container-number': str(index)}},
             'NetworkSettings': {'Ports': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': port}]},
                                 'Networks': {'owned_default': {'IPAddress': address}}}}
            for index, port, address in [(2, '43002', '10.0.0.2'), (1, '43001', '10.0.0.1')]]
        with patch('latency.command', return_value=json.dumps(containers).encode()):
            self.assertEqual(stack.endpoints(), {
                'direct': {'host': ['http://127.0.0.1:43001', 'http://127.0.0.1:43002'],
                           'container': ['http://10.0.0.1:8080', 'http://10.0.0.2:8080']},
                'balanced': {'host': ['http://127.0.0.1:43000'],
                             'container': ['http://load-balancer:8080']}})
        containers[0]['NetworkSettings']['Ports']['8080/tcp'][0]['HostIp'] = '0.0.0.0'
        with patch('latency.command', return_value=json.dumps(containers).encode()), \
                self.assertRaisesRegex(AssertionError, 'loopback'):
            stack.endpoints()

    def test_client_credentials_use_stdin_only_for_both_origins(self):
        stack = Mock()
        stack.settings = {'startupTimeoutSeconds': 120}
        job = {'keys': ['synthetic-key.secret-value'], 'durationSeconds': 2, 'requestTimeoutSeconds': 45}
        for origin in ('host', 'container'):
            with self.subTest(origin=origin), patch('latency.command', return_value=b'{}') as host:
                stack.compose.return_value = b'{}'
                self.assertEqual(latency.measure(stack, job, origin), {})
                invocation = host.call_args if origin == 'host' else stack.compose.call_args
                self.assertNotIn(job['keys'][0], repr(invocation.args))
                self.assertEqual(json.loads(invocation.kwargs['data']), job)
                self.assertEqual(invocation.kwargs['timeout'], 167)
                stack.compose.reset_mock()


class ReconciliationTest(unittest.TestCase):
    def test_acknowledged_and_uncertain_writes_are_reconciled_without_retries(self):
        stack = Mock()
        stack.sql.return_value = 'latency-7-0\nlatency-7-1'
        samples = [{'index': index, 'status': status}
                   for index, status in enumerate((201, 503, 0, 429, 422))]
        with patch('latency.invariant_counts', return_value={'transactions': 2}):
            result = latency.reconcile(stack, {'workload': 'hot', 'runId': 'latency-7'}, samples)
        self.assertEqual(result, {'transactions': 2, 'acknowledged': 1, 'uncertain': 2,
                                  'uncertainCommitted': 1})
        self.assertIn("scope='latency'", stack.sql.call_args.args[0])
        self.assertIn("LIKE 'latency-7-%'", stack.sql.call_args.args[0])
        stack.request.assert_not_called()

    def test_missing_acknowledged_and_committed_rejected_or_unoffered_writes_fail(self):
        samples = [{'index': 0, 'status': 201}, {'index': 1, 'status': 429}]
        for present in ('', 'latency-7-0\nlatency-7-1', 'latency-7-0\nlatency-7-99'):
            with self.subTest(present=present), patch('latency.invariant_counts', return_value={}):
                stack = Mock()
                stack.sql.return_value = present
                with self.assertRaises(AssertionError):
                    latency.reconcile(stack, {'workload': 'distinct', 'runId': 'latency-7'}, samples)

    def test_unsafe_prefix_is_rejected_before_event_lookup(self):
        for prefix in ("x' OR true--", 'latency_%', '', 'UPPER', 'has space'):
            with self.subTest(prefix=prefix), patch('latency.invariant_counts', return_value={}):
                stack = Mock()
                with self.assertRaisesRegex(AssertionError, 'Unsafe diagnostic ID'):
                    latency.reconcile(stack, {'workload': 'distinct', 'runId': prefix}, [])
                stack.sql.assert_not_called()

    def test_read_case_checks_financial_invariants_without_write_reconciliation(self):
        stack = Mock()
        counts = {'transactions': 11, 'evaluations': 11, 'incomplete': 0}
        with patch('latency.invariant_counts', return_value=counts) as invariants:
            self.assertEqual(latency.reconcile(stack, {'workload': 'read'}, []), counts)
        invariants.assert_called_once_with(stack)
        stack.sql.assert_not_called()
        stack.request.assert_not_called()


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        invariants = patch('latency.invariant_counts', return_value={'transactions': 0})
        self.invariants = invariants.start()
        self.addCleanup(invariants.stop)

    @staticmethod
    def setup_stack(directory):
        stack = Mock()
        stack.output = Path(directory)
        stack.keys, stack.rules = ['generated-key.secret'], [{}, {}, {}, {}, {}]
        stack.base_cached = {}
        stack.endpoints.return_value = {
            'direct': {'host': ['http://127.0.0.1:43001']},
            'balanced': {'host': ['http://127.0.0.1:43000']}}
        def request(path, event=None, **_):
            seeded = event is not None and event['eventId'].startswith('latency-seed-')
            return (201 if seeded else 200), b'{"persisted":"same"}', .01
        stack.request.side_effect = request
        stack.sql.return_value = 'stored-policy-hash'
        return stack

    @staticmethod
    def values():
        return {**example(), 'replicas': [1], 'origins': ['host'], 'connections': ['reuse'],
                'workloads': ['read'], 'repetitions': 1, 'seedEvents': 2, 'readPageSize': 1}

    @staticmethod
    def measurement(status=200, slow=0, dropped=0):
        return {'samples': [{'index': 0, 'principalIndex': 0, 'status': status}],
                'summary': {'slowRequests': slow, 'dropped': dropped}}

    def test_samples_and_partial_result_survive_a_reconciliation_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = self.setup_stack(directory)
            records = []

            def record(name, result):
                if name == 'latency-seed':
                    return
                self.assertTrue((stack.output / result['samplesFile']).exists())
                records.append((name, copy.deepcopy(result)))

            with patch('latency.measure', return_value=self.measurement()) as measure, \
                    patch('latency.reconcile', side_effect=AssertionError('broken financial invariant')), \
                    self.assertRaisesRegex(AssertionError, 'broken financial invariant'):
                latency.run(stack, self.values(), record)
            self.assertEqual(measure.call_count, 1)
            self.assertEqual(len(records), 1)
            result = records[0][1]
            self.assertEqual(json.loads((stack.output / result['samplesFile']).read_text())[0]['status'], 200)
            self.assertEqual(result['policyHash'], 'stored-policy-hash')
            self.assertEqual(result['retries'], 0)
            self.assertNotIn(stack.keys[0], json.dumps(records))

    def test_failed_http_stops_subsequent_cases(self):
        for status in (0, 503):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                stack = self.setup_stack(directory)
                records = []
                with patch('latency.measure', return_value=self.measurement(status)), \
                        patch('latency.reconcile', return_value={'transactions': 0}), \
                        self.assertRaises(AssertionError):
                    latency.run(stack, self.values(), lambda name, result: records.append((name, copy.deepcopy(result))))
                self.assertEqual(len(records), 3)
                self.assertEqual(len(list(stack.output.glob('*.json'))), 1)
                self.assertNotIn('latency-conclusion', [name for name, _ in records])

    def test_generator_drops_retain_all_comparisons_and_fail_the_final_conclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = self.setup_stack(directory)
            records = []
            with patch('latency.measure', side_effect=lambda *_: self.measurement(dropped=1)), \
                    patch('latency.reconcile', return_value={'transactions': 0}), \
                    self.assertRaisesRegex(AssertionError, 'generator-limit observations retained'):
                latency.run(stack, self.values(), lambda name, result: records.append((name, copy.deepcopy(result))))
            self.assertEqual(len(list(stack.output.glob('*.json'))), 2)
            name, conclusion = records[-1]
            self.assertEqual(name, 'latency-conclusion')
            self.assertEqual(len(conclusion['generatorLimitedCases']), 2)
            self.assertFalse(conclusion['slowCases'])
            self.assertIn('did not deliver the full offered workload', conclusion['interpretation'])

    def test_read_workload_cannot_silently_change_financial_state(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = self.setup_stack(directory)
            records = []
            with patch('latency.measure', return_value=self.measurement()), \
                    patch('latency.reconcile', return_value={'transactions': 1}), \
                    self.assertRaisesRegex(AssertionError, 'Read workload changed financial state'):
                latency.run(stack, self.values(), lambda name, result: records.append((name, copy.deepcopy(result))))
            self.assertEqual(records[-1][1]['invariants']['transactions'], 1)
            self.assertEqual(len(list(stack.output.glob('*.json'))), 1)

    def test_slow_observations_retain_every_comparison_then_fail_without_claiming_a_cause(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = self.setup_stack(directory)
            records = []
            with patch('latency.measure', side_effect=lambda *_: self.measurement(slow=1)), \
                    patch('latency.reconcile', return_value={'transactions': 0}), \
                    self.assertRaisesRegex(AssertionError, 'Slow-request or generator-limit observations retained'):
                latency.run(stack, self.values(), lambda name, result: records.append((name, copy.deepcopy(result))))
            name, conclusion = records[-1]
            self.assertEqual(name, 'latency-conclusion')
            self.assertEqual(len(conclusion['slowCases']), 2)
            self.assertFalse(conclusion['productionCapacityEstablished'])
            self.assertFalse(conclusion['originalGeneratorAvailable'])
            self.assertIn('requires isolation', conclusion['interpretation'])
            self.assertEqual(len(list(stack.output.glob('*.json'))), 2)

    def test_writes_preserve_payload_for_explicit_readback_and_identical_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = self.setup_stack(directory)
            jobs = []

            def measure(_, job, origin):
                jobs.append(copy.deepcopy(job))
                return self.measurement(201)

            with patch('latency.measure', side_effect=measure), \
                    patch('latency.reconcile', return_value={'transactions': 1}):
                latency.run(stack, {**self.values(), 'workloads': ['hot']}, lambda *_: None)
            writes = [call for call in stack.request.call_args_list
                      if len(call.args) == 2 and not call.args[1]['eventId'].startswith('latency-seed-')]
            self.assertEqual(len(writes), 2)
            for job, invocation in zip(jobs, writes):
                self.assertEqual(invocation.args[0], '/api/v1/transactions')
                self.assertEqual(invocation.args[1], latency.payload(job, 0))
                self.assertEqual(invocation.kwargs['principal'], 0)


class FailedReportTest(unittest.TestCase):
    def test_cli_retains_partial_evidence_and_failure_and_closes_owned_stack(self):
        spec = importlib.util.spec_from_file_location('latency_cli_test', ROOT / 'scripts/verify-operations.py')
        cli = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cli)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'config').mkdir()
            (root / 'docker').mkdir()
            (root / 'config/verification.example.json').write_text(json.dumps(verification()))
            (root / 'config/latency.example.json').write_text(json.dumps(example()))
            (root / 'docker/haproxy.cfg').write_text('synthetic proxy fixture')
            stack = Mock()
            stack.manifest.return_value = {'revision': 'reviewed-commit'}
            stack.client_image = 'sha256:client'
            stack.failure_logs.return_value = 'safe synthetic diagnostics'

            def fail(_, __, record):
                record('first-case', {'samplesFile': 'first-case.json'})
                raise AssertionError('slow observation retained')

            with patch.object(cli, 'ROOT', root), patch.object(cli.os, 'chdir'), \
                    patch.object(cli.signal, 'signal'), patch.object(cli.latency, 'settings', return_value=example()), \
                    patch.object(cli.latency, 'LatencyStack', return_value=stack), \
                    patch.object(cli.latency, 'run', side_effect=fail), \
                    patch.object(cli, 'command', return_value=b'unix:///synthetic/docker.sock'), \
                    patch.object(cli.sys, 'argv', ['verify-operations.py', '--suite', 'latency']), \
                    self.assertRaisesRegex(AssertionError, 'slow observation retained'):
                cli.main()
            reports = list((root / 'target/operations').glob('*/results.json'))
            self.assertEqual(len(reports), 1)
            report = json.loads(reports[0].read_text())
            self.assertFalse(report['passed'])
            self.assertEqual(report['checks']['first-case']['samplesFile'], 'first-case.json')
            self.assertEqual(report['failure'], {'type': 'AssertionError', 'message': 'slow observation retained'})
            self.assertEqual((reports[0].parent / 'failure.log').read_text(), 'safe synthetic diagnostics')
            stack.close.assert_called_once_with()


if __name__ == '__main__':
    unittest.main()
