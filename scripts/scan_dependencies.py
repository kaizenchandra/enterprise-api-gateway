#!/usr/bin/env python3
"""Query OSV using public Maven package coordinates only. No source, internal names, or credentials are sent."""
import datetime
import json
import shutil
import subprocess
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent;
results = root / 'results';
results.mkdir(exist_ok=True)
bom = json.loads((root / 'target/bom.json').read_text())
packages = {}
for component in bom.get('components', []):
    group = component.get('group', '');
    name = component.get('name');
    version = component.get('version')
    if group and name and version and not group.startswith('io.acme'):
        packages[(group + ':' + name, version)] = {'package': {'ecosystem': 'Maven', 'name': group + ':' + name},
                                                   'version': version}
entries = list(packages.values());
findings = []
for offset in range(0, len(entries), 100):
    batch = entries[offset:offset + 100];
    pending = [(query, []) for query in batch]
    while pending:
        request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                         data=json.dumps({'queries': [query for query, _ in pending]}).encode(),
                                         headers={'Content-Type': 'application/json'}, method='POST')
        response = subprocess.run(['curl', '--fail', '--silent', '--show-error', '--max-time', '30', '--header',
                                   'Content-Type: application/json', '--data-binary', '@-',
                                   'https://api.osv.dev/v1/querybatch'],
                                  input=json.dumps({'queries': [query for query, _ in pending]}), text=True,
                                  capture_output=True, check=True)
        data = json.loads(response.stdout)
        more = []
        for (query, prior), result in zip(pending, data['results']):
            vulnerabilities = prior + result.get('vulns', [])
            if result.get('next_page_token'):
                more.append(({**query, 'page_token': result['next_page_token']}, vulnerabilities))
            elif vulnerabilities:
                findings.append({'package': query['package']['name'], 'version': query['version'],
                                 'vulnerabilities': vulnerabilities})
        pending = more
report = {'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'source': 'https://api.osv.dev/v1/querybatch', 'packages_checked': len(entries),
          'matching_packages': len(findings), 'findings': findings,
          'boundary': 'Public Maven coordinates only; not an OS/container image scan or proof of vulnerability absence.'}
(results / 'dependency-scan.json').write_text(json.dumps(report, indent=2));
shutil.copy2(root / 'target/bom.json', results / 'bom.json');
print(json.dumps(report, indent=2))
raise SystemExit(1 if findings else 0)
