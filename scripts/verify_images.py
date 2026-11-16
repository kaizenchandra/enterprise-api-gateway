#!/usr/bin/env python3
"""Inspect actual running application image contents without exposing environment secrets."""
import hashlib
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
fingerprint = subprocess.check_output(['python3', str(ROOT / 'scripts/source_fingerprint.py')], text=True).strip()
secrets = []
for line in (ROOT / 'docker/.env').read_text().splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        if key in ['CONTROL_TOKEN', 'DATABASE_PASSWORD', 'IDENTITY_PRIVATE_KEY']: secrets.append(
            value.strip('"\'').encode())
records = []
for service in ['gateway', 'gateway2', 'control', 'orders', 'payments', 'inventory']:
    container = 'enterprise-gateway-' + service + '-1'
    data = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))[0]
    assert data['Config']['User'] == '10001:10001', service + ' must be non-root'
    assert data['Config']['Labels']['org.opencontainers.image.revision'] == fingerprint, service + ' source mismatch'
    version = subprocess.check_output(['docker', 'exec', container, 'java', '-version'], stderr=subprocess.STDOUT,
                                      text=True)
    assert 'version "21.' in version, service + ' needs Java 21'
    with tempfile.TemporaryDirectory(prefix='gateway-image-') as directory:
        jar = Path(directory) / 'application.jar';
        subprocess.run(['docker', 'cp', container + ':/app/application.jar', str(jar)], check=True, capture_output=True)
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist();
            libs = [n for n in names if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
            assert not any(any(term in n for term in ['junit', 'mockito', 'testcontainers', 'reactor-test']) for n in
                           libs), service + ' contains test libraries'
            assert not any(n.endswith('/.env') for n in names), service + ' contains env file'
            own = [n for n in names if n.startswith('BOOT-INF/classes/') and not n.endswith('/')]
            classes = [n for n in own if n.endswith('.class')]
            assert classes and all(int.from_bytes(archive.read(n)[6:8], 'big') == 65 for n in
                                   classes), service + ' bytecode is not Java 21'
            for entry in own:
                content = archive.read(entry)
                assert not any(
                    secret and secret in content for secret in secrets), service + ' contains a runtime secret'
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    records.append({'service': service, 'image_id': data['Image'], 'source': fingerprint,
                    'runtime': next(line for line in version.splitlines() if 'version "21.' in line),
                    'user': data['Config']['User'], 'java_class_major': 65, 'jar_sha256': digest,
                    'runtime_libraries': len(libs), 'test_libraries_absent': True, 'generated_secrets_absent': True})
for group in [['gateway', 'gateway2'], ['orders', 'payments', 'inventory']]:
    assert len({r['jar_sha256'] for r in records if r['service'] in group}) == 1, 'Replica jars differ'
result = {'passed': True, 'images': records, 'replica_jars_identical': True,
          'boundary': 'All six actual executable jars inspected; generated secret scan covers application classes/resources, not arbitrary external secret detection.'}
(ROOT / 'results/image-verification.json').write_text(json.dumps(result, indent=2));
print(json.dumps(result, indent=2))
