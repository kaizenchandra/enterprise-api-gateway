#!/usr/bin/env python3
"""Fingerprint first-party build inputs only, never local credentials or installed test packages."""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parent.parent
paths = [root / 'pom.xml']
for name in ['gateway-policy-core', 'gateway-control-plane', 'gateway-data-plane', 'example-service']:
    module = root / name;
    paths.append(module / 'pom.xml');
    paths.extend(p for p in (module / 'src').rglob('*') if p.is_file())
paths.extend(p for p in (root / 'docker').glob('Dockerfile*') if p.is_file())
digest = hashlib.sha256()
for path in sorted(paths): digest.update(str(path.relative_to(root)).encode() + b'\0' + path.read_bytes() + b'\0')
print(digest.hexdigest())
