#!/usr/bin/env python3
import base64
import json
import os
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
values = {}
for line in (root / 'docker/.env').read_text().splitlines():
    if '=' in line and not line.startswith('#'):
        k, v = line.split('=', 1);
        values[k] = v.strip('"\'')
password = os.environ.get('GRAFANA_PASSWORD', values.get('GRAFANA_PASSWORD', 'local-change-before-exposing'))
header = 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()
base = 'http://localhost:' + os.environ.get('GRAFANA_PORT', '48030')


def get(url, auth=False): return json.load(
    urllib.request.urlopen(urllib.request.Request(url, headers={'Authorization': header} if auth else {}), timeout=10))


assert get(base + '/api/health')['database'] == 'ok'
dashboards = get(base + '/api/search', True);
assert any(x['uid'] == 'enterprise-gateway' for x in dashboards)
sources = get(base + '/api/datasources', True);
assert any(x['type'] == 'prometheus' and x['url'] == 'http://prometheus:9090' for x in sources)
prom = 'http://localhost:' + os.environ.get('PROMETHEUS_PORT', '49090')
data = get(prom + '/api/v1/query?query=count(spring_cloud_gateway_requests_seconds_count)');
assert data['data']['result']
result = {'passed': True, 'grafana_health': 'ok', 'dashboard': 'enterprise-gateway', 'prometheus_datasource': True,
          'request_metric_series': int(float(data['data']['result'][0]['value'][1]))}
(root / 'results/dashboard.json').write_text(json.dumps(result, indent=2));
print(json.dumps(result, indent=2))
