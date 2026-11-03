"""Publish only allowlisted security diagnostics, never raw secret findings."""
import json
from pathlib import Path
import sys


COMPONENTS = ('source', 'dependencies', 'image', 'db', 'redis', 'commit-proxy', 'load-balancer')
VULNERABILITY_FIELDS = ('VulnerabilityID', 'PkgName', 'InstalledVersion', 'FixedVersion', 'Severity')


def summarize(component, report, exit_code):
    if component not in COMPONENTS:
        raise ValueError('Unknown security component')
    result = {'component': component, 'scannerExitCode': exit_code,
              'status': 'missing_report', 'vulnerabilities': [], 'secretCount': 0}
    if not report.is_file():
        return result
    try:
        data = json.loads(report.read_text())
        if not isinstance(data, dict) or data.get('SchemaVersion') != 2:
            raise ValueError('Unexpected scan report')
        entries = data.get('Results', [])
        if not isinstance(entries, list):
            raise ValueError('Unexpected scan results')
        vulnerabilities, secret_count = [], 0
        for entry in entries:
            if not isinstance(entry, dict):
                raise ValueError('Unexpected scan result')
            found = entry.get('Vulnerabilities')
            secrets = entry.get('Secrets')
            found = [] if found is None else found
            secrets = [] if secrets is None else secrets
            if not isinstance(found, list) or not isinstance(secrets, list):
                raise ValueError('Unexpected findings')
            if any(not isinstance(secret, dict) for secret in secrets):
                raise ValueError('Unexpected secret finding')
            secret_count += len(secrets)
            for vulnerability in found:
                if not isinstance(vulnerability, dict):
                    raise ValueError('Unexpected vulnerability')
                if vulnerability.get('Severity') not in ('HIGH', 'CRITICAL'):
                    continue
                item = {field: vulnerability.get(field, '') for field in VULNERABILITY_FIELDS}
                if any(not isinstance(value, str) for value in item.values()):
                    raise ValueError('Unexpected vulnerability metadata')
                vulnerabilities.append(item)
        result.update(vulnerabilities=vulnerabilities, secretCount=secret_count)
    except (OSError, UnicodeError, ValueError, TypeError):
        result['status'] = 'invalid_report'
        return result
    if exit_code not in (0, 1) or (exit_code != 0 and not vulnerabilities and not secret_count):
        result['status'] = 'scanner_error'
    else:
        result['status'] = 'findings' if vulnerabilities or secret_count else 'passed'
    return result


def initialize(path):
    summary = {'passed': False, 'components': [
        {'component': component, 'status': 'pending'} for component in COMPONENTS]}
    path.write_text(json.dumps(summary, indent=2) + '\n')


def record(directory, component, exit_code):
    path = directory / 'summary.json'
    summary = json.loads(path.read_text())
    result = summarize(component, directory / (component + '.json'), exit_code)
    summary['components'] = [result if entry['component'] == component else entry
                             for entry in summary['components']]
    summary['passed'] = all(entry['status'] == 'passed' for entry in summary['components'])
    path.write_text(json.dumps(summary, indent=2) + '\n')
    print(f"Security {component}: {result['status']} (scanner exit {exit_code}; "
          f"HIGH/CRITICAL vulnerabilities {len(result['vulnerabilities'])}; "
          f"secret findings {result['secretCount']})")
    for finding in result['vulnerabilities']:
        print(json.dumps(finding, sort_keys=True))
    return result['status'] == 'passed'


if __name__ == '__main__':
    directory = Path(sys.argv[1])
    if len(sys.argv) == 2:
        initialize(directory / 'summary.json')
    else:
        sys.exit(0 if record(directory, sys.argv[2], int(sys.argv[3])) else 1)
