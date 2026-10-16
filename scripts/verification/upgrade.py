"""Qualify logical restoration from the last Debian image, never a live project."""
import copy

from stack import check

LEGACY_DATABASE = 'postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3'


def configure_source(stack):
    # restore-db remains the new image on its separate, initially empty volume.
    current = copy.deepcopy(stack.config['services']['db'])
    probe = copy.deepcopy(current)
    probe['profiles'] = ['upgrade']
    for key in ('ports', 'healthcheck', 'secrets', 'depends_on'):
        probe.pop(key, None)
    probe['volumes'] = [dict(volume, read_only=True) if volume['type'] == 'volume' else volume
                        for volume in probe['volumes']]
    stack.config['services']['guard-probe'] = probe
    source = stack.config['services']['db']
    source['image'] = LEGACY_DATABASE
    source.pop('entrypoint')
    source['environment'].pop('FRAUD_DATABASE_FAMILY')
    source['environment'].pop('POSTGRES_INITDB_ARGS')
    source['volumes'] = [volume for volume in source['volumes']
                         if volume['target'] != '/usr/local/bin/fraud-postgres-entrypoint.sh']
    stack.save()


def verify_guard(stack, record):
    try:
        stack.compose('run', '--rm', '--no-deps', 'guard-probe')
    except RuntimeError as failure:
        check('Unrecognized PostgreSQL data directory' in str(failure), 'Guard failed for an unexpected reason')
    else:
        raise AssertionError('New image accepted an unrecognized legacy data directory')
    check(stack.request('/health/ready', authenticated=False)[0] == 200, 'Guard probe disturbed source')
    record('legacy-volume-guard', {'rejectedBeforeDatabaseStartup': True, 'sourceHealthy': True,
                                  'sourceImage': LEGACY_DATABASE,
                                  'targetImage': stack.config['services']['restore-db']['image']})
