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
import recovery
import seed
import upgrade
from stack import ROOT, Stack, check, command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=('recovery', 'capacity', 'upgrade', 'all'), default='all')
    parser.add_argument('--config', type=Path, default=ROOT / 'config/verification.example.json')
    args = parser.parse_args()
    os.chdir(ROOT)
    settings = json.loads(args.config.read_text())
    reference = json.loads((ROOT / 'config/verification.example.json').read_text())
    check(settings.keys() == reference.keys(), 'Verification configuration has missing/unknown keys')
    check(all(type(value) is int and value > 0 for key, value in settings.items() if key != 'event' and key != 'maxUnexpectedErrorFraction'),
          'Workload settings must be positive integers')
    check(0 < settings['maxUnexpectedErrorFraction'] < 1, 'Invalid error fraction')
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
        stack = Stack(output, settings)
        if args.suite == 'upgrade':
            upgrade.configure_source(stack)
        stack.start()
        if args.suite == 'upgrade':
            upgrade.verify_guard(stack, record)
        report['environment'] = stack.manifest()
        save()
        if args.suite in ('recovery', 'upgrade', 'all'):
            recovery.run(stack, record)
        if args.suite in ('capacity', 'all'):
            record('seed', seed.run(stack))
            benchmark.run(stack, record)
        report['passed'] = True
    except BaseException as failure:
        report['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        if stack:
            # App logs have no request bodies or credential values; still kept ignored/local.
            try:
                (output / 'failure.log').write_bytes(stack.compose('logs', '--no-color', '--tail', '150'))
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
