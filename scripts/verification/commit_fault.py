"""Run the wire fault on the owned Compose network, never through host routing."""
import hashlib
import json

from stack import ROOT, check, command

def build_image():
    dockerfile = ROOT / 'docker/commit-proxy.Dockerfile'
    fingerprint = hashlib.sha256(dockerfile.read_bytes()).hexdigest()
    image = 'fraud-commit-proxy:' + fingerprint
    if not command(['docker', 'image', 'ls', '--quiet', image]).strip():
        command(['docker', 'build', '--tag', image, '--label', 'fraud.test-source=' + fingerprint,
                 '--file', str(dockerfile), str(ROOT / 'docker')])
    details = json.loads(command(['docker', 'image', 'inspect', image]))[0]
    check(details['Config'].get('Labels', {}).get('fraud.test-source') == fingerprint,
          'Cached fault-proxy image does not match its Dockerfile')
    return details['Id']



class ContainerCommitProxy:
    def __init__(self, stack):
        self.stack = stack
        self.image = build_image()
        stack.config['services']['commit-proxy'] = {
            'image': self.image, 'profiles': ['fault'], 'init': True,
            'user': '65534:65534', 'read_only': True,
            'tmpfs': ['/tmp:size=1m,mode=1777'], 'cap_drop': ['ALL'],
            'security_opt': ['no-new-privileges:true'], 'cpus': 0.25,
            'mem_limit': '64m', 'pids_limit': 64,
            'volumes': [{'type': 'bind', 'source': str(ROOT / 'scripts/verification/faults.py'),
                         'target': '/faults.py', 'read_only': True}],
            'command': ['python3', '-B', '/faults.py', 'serve'],
            'healthcheck': {'test': ['CMD', 'python3', '-B', '/faults.py', 'ready'],
                            'interval': '1s', 'timeout': '2s', 'retries': 10}}
        stack.save()
        stack.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout',
                      str(stack.settings['startupTimeoutSeconds']), 'commit-proxy')

    def status(self):
        return json.loads(self.stack.compose('exec', '-T', 'commit-proxy', 'python3', '-B', '/faults.py', 'status'))

    def arm(self):
        self.stack.compose('exec', '-T', 'commit-proxy', 'python3', '-B', '/faults.py', 'arm')
        check(self.status()['armed'], 'Commit proxy did not arm')

    def close(self):
        self.stack.compose('rm', '--stop', '--force', 'commit-proxy')
