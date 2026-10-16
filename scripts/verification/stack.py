"""Own every resource in a uniquely named Compose project; never select a live stack."""
import copy
import hashlib
import json
import os
import platform
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def command(args, data=None, timeout=180):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        # psql can echo its SQL input in stderr, including generated role passwords.
        detail = 'diagnostics withheld for stdin-bearing command' if data is not None else result.stderr.decode(errors='replace')[-1800:]
        raise RuntimeError(f'{args[0]} {args[1]} failed ({result.returncode}): ' + detail)
    return result.stdout


def check(condition, message):
    if not condition:
        raise AssertionError(message)


class Stack:
    def __init__(self, output, settings):
        self.output, self.settings = output, settings
        self.project = 'fraud-ops-' + secrets.token_hex(6)
        self.file = output / 'compose.json'
        self.keys = []
        self.closed = False
        self.credentials = output / 'private'
        self.credentials.mkdir(mode=0o700)
        base = json.loads(command(['docker', 'compose', 'config', '--format', 'json']))
        self.config = base
        base['name'] = self.project
        for section in ('networks', 'volumes'):
            for key, value in base.get(section, {}).items():
                check(not value.get('external'), 'External resources are forbidden in operational tests')
                value['name'] = self.project + '_' + key
        for key, value in base['secrets'].items():
            path = self.credentials / key
            path.write_text(('local.' if key == 'local-api-key' else '') + secrets.token_hex(32) + '\n')
            path.chmod(0o444)
            value['file'] = str(path)
        self.config['services']['app']['ports'] = [self.port_definition(8080)]
        self.config['services']['db']['ports'] = [self.port_definition(5432)]
        self.config['services']['peer'] = copy.deepcopy(self.config['services']['app'])
        self.config['services']['peer']['profiles'] = ['peer']
        self.config['services']['restore-db'] = copy.deepcopy(self.config['services']['db'])
        restored = self.config['services']['restore-db']
        restored['profiles'] = ['restore']
        restored.pop('ports')
        restored['volumes'] = [v for v in restored['volumes'] if v['type'] != 'volume']
        restored['volumes'].append({'type': 'volume', 'source': 'restored-data', 'target': '/var/lib/postgresql/data'})
        self.config['volumes']['restored-data'] = {'name': self.project + '_restored-data'}
        self.config['services']['restore-app'] = copy.deepcopy(self.config['services']['app'])
        restored_app = self.config['services']['restore-app']
        restored_app['profiles'] = ['restore']
        restored_app.pop('depends_on', None)
        restored_app['environment']['DATABASE_URL'] = 'jdbc:postgresql://restore-db:5432/fraud?currentSchema=fraud'
        # Resolve the local tag once; every process must run the same reviewed artifact.
        image = json.loads(command(['docker', 'image', 'inspect', base['services']['app']['image']]))[0]
        self.image = image['Id']
        for service in base['services'].values():
            if service.get('image') == base['services']['app']['image'] or 'build' in service:
                service['image'] = self.image
                service.pop('build', None)
        self.save()

    @staticmethod
    def port_definition(target):
        return {'target': target, 'published': '0', 'host_ip': '127.0.0.1', 'protocol': 'tcp'}

    def save(self):
        self.file.write_text(json.dumps(self.config, indent=2))

    def compose(self, *args, data=None, timeout=180):
        return command(['docker', 'compose', '-p', self.project, '-f', str(self.file), *args], data, timeout)

    def sql(self, query, service='db'):
        return self.compose('exec', '-T', service, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'fraud', '-At',
                            data=query.encode()).decode().strip()

    def container(self, service):
        return self.compose('ps', '-aq', service).decode().strip()

    def address(self, service='app', target=8080):
        return self.compose('port', service, str(target)).decode().strip()

    def base(self, service='app'):
        return 'http://' + self.address(service)

    def request(self, path, event=None, principal=0, service='app', authenticated=True):
        headers = {'Content-Type': 'application/json'}
        if authenticated:
            headers['X-API-Key'] = self.keys[principal]
        request = urllib.request.Request(self.base_cached[service] + path,
                    data=json.dumps(event).encode() if event is not None else None, headers=headers)
        start = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=self.settings['requestTimeoutSeconds']) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as response:
            status, body = response.code, response.read()
        except (OSError, TimeoutError):
            status, body = 0, b'{}'
        return status, body, time.monotonic() - start

    def ready(self, service='app'):
        self.base_cached[service] = self.base(service)
        deadline = time.monotonic() + self.settings['startupTimeoutSeconds']
        last_status = None
        while time.monotonic() < deadline:
            last_status = self.request('/health/ready', service=service, authenticated=False)[0]
            if last_status == 200:
                return
            time.sleep(.25)
        raise AssertionError(f'{service} did not become ready (last HTTP status: {last_status})')

    def start(self):
        self.base_cached = {}
        self.compose('up', '-d', '--no-build', '--wait', '--wait-timeout', str(self.settings['startupTimeoutSeconds']), 'app')
        self.ready()
        self.provision()

    def provision(self):
        statements = []
        for index in range(self.settings['principals']):
            secret = secrets.token_hex(32)
            self.keys.append(f'ops-{index}.{secret}')
            digest = hashlib.sha256(secret.encode()).hexdigest()
            statements += [f"INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES ('ops-{index}','ops-{index}',true,ARRAY['INGEST','READ','POLICY'],ARRAY['VERIFIED_CARD_PRESENT_TERMINAL']);",
                f"INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) VALUES ('ops-{index}','ops-{index}','{digest}',clock_timestamp(),clock_timestamp()+interval '1 day');"]
        self.sql('\n'.join(statements))
        status, body, _ = self.request('/api/v1/rules')
        check(status == 200, 'Could not capture active rule metadata')
        self.rules = json.loads(body)['rules']
        check(all(rule['enabled'] for rule in self.rules), 'Qualification requires every installed rule enabled')

    def peer(self):
        self.compose('up', '-d', '--no-build', '--no-deps', 'peer')
        self.ready('peer')

    def stop_peer(self):
        self.compose('stop', 'peer')

    def manifest(self):
        info = json.loads(command(['docker', 'info', '--format', '{{json .}}']))
        names = sorted(set(command(['git', 'ls-files', '-co', '--exclude-standard', '-z']).decode().split('\0')) - {''})
        tree = hashlib.sha256()
        for name in names:
            path = ROOT / name
            if path.is_file():
                tree.update(name.encode() + b'\0' + path.read_bytes() + b'\0')
        return {'sourceTreeSha256': tree.hexdigest(), 'java': self.compose('exec', '-T', 'app', 'java', '--version').decode().strip(),
                'serviceConfiguration': (ROOT / 'config/service.example.yaml').read_text(),
                'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'revision': command(['git', 'rev-parse', 'HEAD']).decode().strip(),
                'dirty': bool(command(['git', 'status', '--porcelain']).strip()),
                'image': self.image, 'host': {'os': platform.platform(), 'machine': platform.machine(), 'cpuCount': os.cpu_count()},
                'docker': {key: info.get(key) for key in ('ServerVersion', 'NCPU', 'MemTotal', 'Architecture', 'OperatingSystem')},
                'services': {name: {key: service.get(key) for key in ('image', 'mem_limit', 'cpus', 'pids_limit')}
                             for name, service in self.config['services'].items()},
                'ruleMetadata': [{'ruleId': rule['ruleId'], 'algorithmVersion': rule['algorithmVersion']} for rule in self.rules],
                'policyHash': self.sql('SELECT policy_hash FROM fraud.policy_head WHERE singleton'),
                'configurationHashes': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
                                        for name in ('config/service.example.yaml', 'config/policy.example.yaml', 'config/verification.example.json')}}

    def failure_logs(self):
        logs = self.compose('logs', '--no-color', '--tail', '150').decode(errors='replace')
        secrets_to_remove = [path.read_text().strip() for path in self.credentials.iterdir()]
        secrets_to_remove += self.keys
        secrets_to_remove += [value.split('.', 1)[1] for value in secrets_to_remove if '.' in value]
        for secret in sorted(set(secrets_to_remove), key=len, reverse=True):
            if secret:
                logs = logs.replace(secret, '[REDACTED]')
        return logs

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.compose('--profile', '*', 'down', '--volumes', '--remove-orphans')
        finally:
            for path in self.credentials.iterdir():
                path.unlink()
            self.credentials.rmdir()
