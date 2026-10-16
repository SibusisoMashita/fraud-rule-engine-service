"""Fail if the packaged dependency inventory omits any executable nested library."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(bom, jar):
    known = {entry['content'] for component in bom.get('components', [])
             for entry in component.get('hashes', []) if entry['alg'] == 'SHA-256'}
    missing = []
    with zipfile.ZipFile(jar) as archive:
        libraries = [name for name in archive.namelist() if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')]
        for name in libraries:
            if hashlib.sha256(archive.read(name)).hexdigest() not in known:
                missing.append(name)
    if missing or not libraries:
        raise AssertionError('Packaged libraries missing from SBOM: ' + repr(missing))
    return {'packagedLibraries': len(libraries), 'matchedSha256': len(libraries),
            'sbomComponents': len(bom['components']), 'jarSha256': hashlib.sha256(Path(jar).read_bytes()).hexdigest()}


if __name__ == '__main__':
    print(json.dumps(verify(json.loads(Path(sys.argv[1]).read_text()), sys.argv[2]), indent=2))
