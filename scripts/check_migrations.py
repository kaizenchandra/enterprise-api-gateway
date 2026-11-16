#!/usr/bin/env python3
"""Protect already-applied migrations; new schema changes must use a new version."""
import zlib
from pathlib import Path

root = Path(__file__).resolve().parent.parent / 'gateway-control-plane/src/main/resources/db/migration'
for name, expected in {'V1__registry.sql': 960153842, 'V2__key_reconciliation.sql': 254000537}.items():
    crc = 0
    for line in (root / name).read_text().splitlines(): crc = zlib.crc32(line.encode(), crc)
    signed = crc if crc < 2 ** 31 else crc - 2 ** 32
    if signed != expected: raise SystemExit(
        f'Immutable migration {name} changed: expected {expected}, got {signed}. Restore it; create a new migration for schema changes.')
print('PASS immutable migration checksums')
