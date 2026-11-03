#!/usr/bin/env python3
"""Reproducible Stage 9 evidence; stdlib only, local Docker daemon required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent / 'verification'))
import benchmark
import latency
import recovery
import seed
import upgrade
from stack import ROOT, Stack, check, command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=('recovery', 'capacity', 'upgrade', 'latency', 'all'), default='all')
    parser.add_argument('--config', type=Path, default=ROOT / 'config/verification.example.json')
    parser.add_argument('--latency-config', type=Path, default=ROOT / 'config/latency.example.json')
    parser.add_argument('--app-image', help='Use an already-built application image without changing the local demo tag')
    args = parser.parse_args()
    os.chdir(ROOT)
    settings = json.loads(args.config.read_text())
    reference = json.loads((ROOT / 'config/verification.example.json').read_text())
    check(settings.keys() == reference.keys(), 'Verification configuration has missing/unknown keys')
    check(all(type(value) is int and value > 0 for key, value in settings.items() if key != 'event' and key != 'maxUnexpectedErrorFraction'),
          'Workload settings must be positive integers')
    check(0 < settings['maxUnexpectedErrorFraction'] < 1, 'Invalid error fraction')
    latency_values = latency.settings(args.latency_config) if args.suite == 'latency' else None
    if latency_values:
        settings.update({key: latency_values[key] for key in ('principals', 'requestTimeoutSeconds',
                         'applicationConnectionBudget', 'rolloutSurge')})
    if args.suite == 'upgrade':
        settings['event']['category'] = 'CAFÉ / 日本'
    endpoint = os.environ.get('DOCKER_HOST') or command(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).decode().strip()
    check(endpoint.startswith('unix://'), 'This harness requires a local Unix-socket Docker engine')
    output = ROOT / 'target' / 'operations' / (time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + str(os.getpid()))
    output.mkdir(parents=True)
    output.chmod(0o700)
    print('Operational evidence: ' + str(output), flush=True)
    report = {'suite': args.suite, 'settings': settings, 'settingsSha256': hashlib.sha256(args.config.read_bytes()).hexdigest(),
              'standardWorkload': settings == reference, 'checks': {}, 'passed': False}
    if latency_values:
        report.update(latencySettings=latency_values,
                      latencySettingsSha256=hashlib.sha256(args.latency_config.read_bytes()).hexdigest(),
                      standardWorkload=latency_values == json.loads((ROOT / 'config/latency.example.json').read_text()))
    stack = None

    def save():
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')

    def record(name, result):
        report['checks'][name] = result
        save()
        print('Recorded ' + name, flush=True)

    def terminate(*_):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, terminate)
    try:
        stack = (latency.LatencyStack(output, settings, latency_values, args.app_image) if latency_values else
                 Stack(output, settings, args.app_image))
        if args.suite == 'upgrade':
            upgrade.configure_source(stack)
        stack.start()
        if args.suite == 'upgrade':
            upgrade.verify_guard(stack, record)
        report['environment'] = stack.manifest()
        if latency_values:
            report['environment'].update(
                dockerContext=command(['docker', 'context', 'show']).decode().strip(),
                dockerEndpoint=endpoint,
                clientImage=stack.client_image,
                proxyConfigurationSha256=hashlib.sha256((ROOT / 'docker/haproxy.cfg').read_bytes()).hexdigest())
        save()
        if args.suite in ('recovery', 'upgrade', 'all'):
            recovery.run(stack, record)
        if args.suite in ('capacity', 'all'):
            record('seed', seed.run(stack))
            benchmark.run(stack, record)
        if latency_values:
            latency.run(stack, latency_values, record)
        report['passed'] = True
    except BaseException as failure:
        report['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        if stack:
            # Only disposable synthetic fixtures are logged; redact generated credentials.
            try:
                (output / 'failure.log').write_text(stack.failure_logs())
            except Exception:
                pass
        raise
    finally:
        try:
            if stack:
                stack.close()
            else:
                # Construction can fail after private files exist, before ownership is returned.
                private = output / 'private'
                if private.exists():
                    for path in private.iterdir():
                        path.unlink()
                    private.rmdir()
        except Exception as cleanup:
            report['passed'] = False
            report['cleanupFailure'] = type(cleanup).__name__
            raise
        finally:
            save()
        print('Evidence retained in ' + str(output / 'results.json'), flush=True)
    print('Operational verification passed.', flush=True)


if __name__ == '__main__':
    main()
