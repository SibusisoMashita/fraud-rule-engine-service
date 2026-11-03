import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from security_summary import COMPONENTS, initialize, record, summarize


class SecuritySummaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.report = self.directory / 'source.json'

    def write(self, **values):
        self.report.write_text(json.dumps({'SchemaVersion': 2, **values}))

    def test_only_allowlisted_vulnerability_metadata_and_secret_counts_are_published(self):
        private = 'SYNTHETIC-PRIVATE-VALUE-DO-NOT-PUBLISH'
        self.write(ArtifactName=private, Metadata={'secret': private}, Results=[{
            'Target': private, 'Class': private,
            'Vulnerabilities': [{'VulnerabilityID': 'CVE-test', 'PkgName': 'example',
                                 'InstalledVersion': '1', 'FixedVersion': '2', 'Severity': 'HIGH',
                                 'Description': private, 'Title': private, 'Secret': private}],
            'Secrets': [{'RuleID': private, 'Match': private, 'Text': private,
                         'Code': {'Lines': [{'Content': private}]}, 'StartLine': 123}]}])
        initialize(self.directory / 'summary.json')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertFalse(record(self.directory, 'source', 1))
        summary = json.loads((self.directory / 'summary.json').read_text())
        result = summary['components'][0]
        self.assertEqual(result['status'], 'findings')
        self.assertEqual(result['secretCount'], 1)
        self.assertEqual(result['vulnerabilities'], [{
            'VulnerabilityID': 'CVE-test', 'PkgName': 'example', 'InstalledVersion': '1',
            'FixedVersion': '2', 'Severity': 'HIGH'}])
        self.assertNotIn(private, json.dumps(summary) + output.getvalue())
        self.assertNotIn('StartLine', json.dumps(summary))

    def test_valid_empty_reports_pass_but_all_components_must_complete(self):
        initialize(self.directory / 'summary.json')
        for index, component in enumerate(COMPONENTS):
            (self.directory / (component + '.json')).write_text(
                json.dumps({'SchemaVersion': 2, 'Results': []}))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertTrue(record(self.directory, component, 0))
            summary = json.loads((self.directory / 'summary.json').read_text())
            self.assertEqual(summary['passed'], index == len(COMPONENTS) - 1)

    def test_missing_and_malformed_reports_cannot_pass_even_with_success_exit(self):
        for code in (0, 1, 2):
            with self.subTest(exit_code=code):
                self.assertEqual(summarize('source', self.report, code)['status'], 'missing_report')
        for malformed in ('not JSON', '[]', '{}', '{"SchemaVersion": 2, "Results": {}}',
                          '{"SchemaVersion": 2, "Results": [null]}',
                          '{"SchemaVersion": 2, "Results": [{"Secrets": {}}]}',
                          '{"SchemaVersion": 2, "Results": [{"Secrets": ["private"]}]}'):
            with self.subTest(report=malformed):
                self.report.write_text(malformed)
                self.assertEqual(summarize('source', self.report, 0)['status'], 'invalid_report')

    def test_scanner_errors_remain_failures_with_or_without_findings(self):
        self.write(Results=[])
        for code in (1, 2, 127):
            self.assertEqual(summarize('source', self.report, code)['status'], 'scanner_error')
        self.write(Results=[{'Secrets': [{'Match': 'private'}]}])
        self.assertEqual(summarize('source', self.report, 2)['status'], 'scanner_error')
        self.assertEqual(summarize('source', self.report, 2)['secretCount'], 1)

    def test_findings_cannot_pass_even_if_scanner_returns_zero(self):
        self.write(Results=[{'Vulnerabilities': [{'VulnerabilityID': 'CVE-test',
                                                 'Severity': 'CRITICAL'}]}])
        result = summarize('source', self.report, 0)
        self.assertEqual(result['status'], 'findings')
        self.assertEqual(result['vulnerabilities'][0]['FixedVersion'], '')

    def test_invalid_metadata_fails_without_copying_it_to_summary(self):
        self.write(Results=[{'Vulnerabilities': [{'Severity': 'HIGH', 'PkgName': {'Match': 'private'}}]}])
        result = summarize('source', self.report, 1)
        self.assertEqual(result['status'], 'invalid_report')
        self.assertEqual(result['vulnerabilities'], [])
        self.assertNotIn('private', json.dumps(result))

    def test_cli_returns_failure_and_does_not_echo_malformed_private_input(self):
        initialize(self.directory / 'summary.json')
        self.report.write_text('malformed private secret value')
        completed = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('security_summary.py')),
                                    str(self.directory), 'source', '1'], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 1)
        self.assertIn('invalid_report', completed.stdout)
        self.assertNotIn('private secret value', completed.stdout + completed.stderr)


class SecurityCommandTest(unittest.TestCase):
    def run_gate(self, mode='clean', image=None, inventory_exit=0, scanner_version='0.76.0'):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        verification = root / 'scripts/verification'
        verification.mkdir(parents=True)
        source = Path(__file__).parent
        shutil.copy(source.parent / 'security-check.sh', root / 'scripts/security-check.sh')
        shutil.copy(source / 'security_summary.py', verification / 'security_summary.py')
        (verification / 'inventory.py').write_text(f'raise SystemExit({inventory_exit})\n')
        (verification / 'commit_fault.py').write_text('def build_image(): return "fixture-proxy"\n')
        binaries = root / 'bin'
        binaries.mkdir()
        docker = binaries / 'docker'
        docker.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
args = sys.argv[1:]
with open('docker-calls.jsonl', 'a') as output: output.write(json.dumps(args) + '\\n')
if args[:2] == ['image', 'inspect']: print('sha256:fixture')
elif args[0] == 'create': print('fixture-container')
elif args[0] == 'cp': pathlib.Path(args[-1]).write_text('{}')
elif args[0] == 'compose':
    print(json.dumps({'services': {name: {'image': name + ':fixture'} for name in ('db', 'redis', 'load-balancer')}}))
''')
        docker.chmod(0o755)
        scanner = binaries / 'trivy'
        scanner.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
if args == ['--version']:
    print('Version: ' + os.environ['SCANNER_VERSION'])
    raise SystemExit(0)
path = pathlib.Path(args[args.index('--output') + 1])
if path.exists(): pathlib.Path('stale-report-observed').touch()
if 'fs' in args and os.environ['SCAN_MODE'] == 'error': raise SystemExit(2)
entries = [{'Secrets': [{'Match': 'PRIVATE-FAKE-KEY'}]}] if 'fs' in args and os.environ['SCAN_MODE'] == 'finding' else []
path.write_text(json.dumps({'SchemaVersion': 2, 'Results': entries}))
raise SystemExit(1 if entries else 0)
''')
        scanner.chmod(0o755)
        security = root / 'target/security'
        security.mkdir(parents=True)
        for component in COMPONENTS:
            (security / (component + '.json')).write_text('{"SchemaVersion":2,"Results":[]}')
        (security / 'summary.json').write_text('{"passed":true,"private":"PRIVATE-OLD-REPORT"}')
        env = {**os.environ, 'PATH': str(binaries) + os.pathsep + os.environ['PATH'],
               'TRIVY_BIN': str(scanner), 'SCAN_MODE': mode, 'SCANNER_VERSION': scanner_version}
        env.pop('APP_IMAGE', None)
        if image:
            env['APP_IMAGE'] = image
        completed = subprocess.run(['sh', str(root / 'scripts/security-check.sh')], cwd=root,
                                   env=env, capture_output=True, text=True)
        return root, completed

    def test_clean_scan_resolves_configured_image_and_replaces_stale_reports(self):
        root, completed = self.run_gate(image='candidate:review')
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        summary = json.loads((root / 'target/security/summary.json').read_text())
        self.assertTrue(summary['passed'])
        self.assertFalse((root / 'stale-report-observed').exists())
        self.assertEqual((root / 'target/security/image-id.txt').read_text(), 'sha256:fixture\n')
        calls = [json.loads(line) for line in (root / 'docker-calls.jsonl').read_text().splitlines()]
        self.assertEqual(calls[0][-1], 'candidate:review')

    def test_scanner_error_cannot_reuse_a_stale_clean_report(self):
        root, completed = self.run_gate(mode='error')
        self.assertEqual(completed.returncode, 1)
        self.assertIn('source: missing_report (scanner exit 2', completed.stdout)
        summary = json.loads((root / 'target/security/summary.json').read_text())
        self.assertFalse(summary['passed'])
        self.assertFalse((root / 'target/security/source.json').exists())
        self.assertEqual(summary['components'][-1]['status'], 'passed')

    def test_secret_finding_fails_without_printing_or_publishing_match(self):
        root, completed = self.run_gate(mode='finding')
        self.assertEqual(completed.returncode, 1)
        public = (root / 'target/security/summary.json').read_text() + completed.stdout + completed.stderr
        self.assertNotIn('PRIVATE-FAKE-KEY', public)
        self.assertIn('secret findings 1', completed.stdout)
        calls = [json.loads(line) for line in (root / 'docker-calls.jsonl').read_text().splitlines()]
        self.assertEqual(calls[0][-1], 'fraud-rule-engine-service:local')

    def test_inventory_failure_stops_before_scanning(self):
        root, completed = self.run_gate(inventory_exit=1)
        self.assertEqual(completed.returncode, 1)
        summary = json.loads((root / 'target/security/summary.json').read_text())
        self.assertFalse(summary['passed'])
        self.assertTrue(all(entry['status'] == 'pending' for entry in summary['components']))
        self.assertNotIn('scanning', completed.stdout)

    def test_wrong_scanner_version_clears_old_success_and_stops_before_docker(self):
        root, completed = self.run_gate(scanner_version='0.1.0')
        self.assertEqual(completed.returncode, 1)
        report = (root / 'target/security/summary.json').read_text()
        summary = json.loads(report)
        self.assertFalse(summary['passed'])
        self.assertTrue(all(entry['status'] == 'pending' for entry in summary['components']))
        self.assertFalse((root / 'docker-calls.jsonl').exists())
        self.assertNotIn('PRIVATE-OLD-REPORT', report + completed.stdout + completed.stderr)
        self.assertIn('Trivy 0.76.0 is required', completed.stderr)


if __name__ == '__main__':
    unittest.main()
