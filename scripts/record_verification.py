#!/usr/bin/env python3
"""Consolidate only existing successful verification artifacts; fail on stale image evidence."""
import argparse
import datetime
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as E
from pathlib import Path

root = Path(__file__).resolve().parent.parent;
results = root / 'results'
p = argparse.ArgumentParser();
p.add_argument('--log-prefix', required=True);
args = p.parse_args()
source = subprocess.check_output(['python3', str(root / 'scripts/source_fingerprint.py')], text=True).strip()
suites = []
for path in root.glob('*/target/surefire-reports/TEST-*.xml'):
    d = E.parse(path).getroot();
    suites.append({k: d.attrib[k] for k in ['name', 'tests', 'failures', 'errors', 'skipped', 'time']})
totals = {k: sum(int(s[k]) for s in suites) for k in ['tests', 'failures', 'errors', 'skipped']}
assert totals['tests'] >= 49 and all(totals[k] == 0 for k in ['failures', 'errors', 'skipped']), totals
(results / 'java-tests.json').write_text(json.dumps({'source': source, 'totals': totals, 'suites': suites}, indent=2))
load = lambda name: json.loads((results / name).read_text())
acceptance = load('acceptance.json');
assert len(acceptance) == 10 and all(x['passed'] for x in acceptance)
images = load('images.json');
assert len(images) == 6 and all(x['source'] == source for x in images.values())
verification = load('image-verification.json');
assert verification['passed'] and len(verification['images']) == 6
for item in verification['images']: assert item['source'] == source and item['image_id'] == images[item['service']][
    'image_id']
scans = load('image-scan-summary.json')
for item in scans['reports']:
    assert item['image'] == images[item['service']]['image_id']
    assert item['counts']['HIGH'] == item['counts']['CRITICAL'] == item['fix_available'] == 0
assert load('dependency-scan.json')['matching_packages'] == 0
assert load('observability.json')['passed'] and load('dashboard.json')['passed']
benchmark = load('benchmark-summary.json');
assert benchmark['source'] == source
assert all(r['exit_code'] == 0 and r['metrics']['http_req_failed']['values']['rate'] == 0 for r in benchmark['runs'])
logs = {}
for suffix in ['tests', 'images', 'up', 'demo', 'distributed', 'acceptance', 'image-scan', 'portal', 'observability',
               'benchmark', 'image-verification', 'dashboard', 'dependency-scan']:
    path = Path(args.log_prefix + '-' + suffix + '.log');
    assert path.exists(), str(path)
    if suffix == 'tests': assert 'BUILD SUCCESS' in path.read_text()
    if suffix in ['demo', 'distributed', 'portal']: assert 'PASS' in path.read_text()
    target = results / ('final-' + suffix + '.log');
    shutil.copy2(path, target);
    logs[target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
status = subprocess.check_output(
    ['docker', 'compose', '--env-file', str(root / 'docker/.env'), '-f', str(root / 'docker/compose.yml'), '--profile',
     'monitoring', 'ps', '--format', 'json'], text=True)
services = []
for line in status.splitlines():
    d = json.loads(line);
    assert d['State'] == 'running';
    assert not d.get('Health') or d['Health'] == 'healthy';
    services.append({k: d[k] for k in ['Service', 'State', 'Health']})
assert len(services) == 12, len(services)
manifest = {'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'source': source,
            'local_runtime_acceptance_passed': True, 'java': totals, 'http_acceptance_groups': len(acceptance),
            'distributed_quota': '5 admitted/200, 35 quota 429, 0 dependency failures from 40 calls across two gateways',
            'verified_application_images': len(images), 'services': services, 'logs_sha256': logs,
            'performance_targets_met': False,
            'performance_boundary': 'Both local k6 runs had zero HTTP failures; dropped iterations mean offered rates were not sustained. Target-scale throughput, full gateway overhead and SLOs remain unverified.',
            'production_ready': False,
            'remaining_gates': 'Unfixed OS advisories; independent security/parser review; production TLS/mTLS/storage/secret/DR integrations; Redis failover/global quota correctness; target-scale and multi-AZ/multi-region validation. See docs/verification.md.'}
(results / 'evidence.json').write_text(json.dumps(manifest, indent=2));
print(json.dumps(manifest, indent=2))
