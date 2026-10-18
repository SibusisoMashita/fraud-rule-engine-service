import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

from stack import ROOT


class PolicyAdministrationSetupTest(unittest.TestCase):
    def test_optional_secrets_preserve_existing_credentials_without_printing_them(self):
        with tempfile.TemporaryDirectory() as folder:
            checkout = Path(folder)
            scripts = checkout / 'scripts'
            scripts.mkdir()
            for name in ('init-secrets.sh', 'init-ui-demo-secrets.sh'):
                shutil.copy2(ROOT / 'scripts' / name, scripts / name)

            def run(name):
                return subprocess.run(['sh', str(scripts / name)], check=True, capture_output=True)

            ordinary = run('init-secrets.sh')
            directory = checkout / 'secrets'
            original = {path.name: path.read_bytes() for path in directory.iterdir()}
            self.assertNotIn('fraud.policy-management.password', original)
            self.assertNotIn('local-policy-admin-api-key', original)

            first = run('init-ui-demo-secrets.sh')
            credentials = {path.name: path.read_bytes() for path in directory.iterdir()}
            self.assertEqual(len(credentials), len(original) + 2)
            self.assertEqual({name: credentials[name] for name in original}, original)
            self.assertRegex(credentials['local-policy-admin-api-key'].decode().strip(),
                             r'^policy-admin\.[a-f0-9]{64}$')
            self.assertRegex(credentials['fraud.policy-management.password'].decode().strip(),
                             r'^[a-f0-9]{64}$')
            second = run('init-ui-demo-secrets.sh')
            self.assertEqual({path.name: path.read_bytes() for path in directory.iterdir()}, credentials)
            output = ordinary.stdout + ordinary.stderr + first.stdout + first.stderr + second.stdout + second.stderr
            for value in credentials.values():
                self.assertNotIn(value.strip(), output)
            if os.name == 'posix':
                self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
                for path in directory.iterdir():
                    self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o444)
