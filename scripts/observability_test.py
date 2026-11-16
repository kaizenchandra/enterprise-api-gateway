#!/usr/bin/env python3
"""Live W3C traces, Prometheus, credential-log canaries and collector outage test."""
import datetime
import json
import os
import subprocess
import time
import urllib.request
import uuid
from pathlib import Path

from demo import request, admin, token
from fixtures import converge

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ['docker', 'compose', '--env-file', str(ROOT / 'docker/.env'), '-f', str(ROOT / 'docker/compose.yml')]


def compose(*args): return subprocess.check_output([*COMPOSE, *args], cwd=ROOT, text=True, stderr=subprocess.STDOUT)


def get_json(url): return json.load(urllib.request.urlopen(url, timeout=10))


def main():
    a = token('admin');
    u = token('demo');
    converge('/api/v1/orders', u)
    status, key = request('POST', admin + 'consumers/demo-client/keys',
                          {'scopes': ['orders:read'], 'roles': [], 'days': 1}, a)
    assert status == 201
    start = datetime.datetime.now(datetime.timezone.utc).isoformat();
    trace = uuid.uuid4().hex;
    canary = 'sensitive-' + uuid.uuid4().hex
    try:
        headers = {'traceparent': '00-' + trace + '-0123456789abcdef-01'}
        assert request('GET', 'http://localhost:48080/api/v1/orders?canary=' + canary, bearer=u, extra=headers)[
                   0] == 200
        assert request('GET', 'http://localhost:48082/api/v1/orders', key=key['secret'])[0] == 200
        logs = ''
        for _ in range(30):
            time.sleep(1);
            logs = compose('logs', '--since', start, 'collector')
            if trace in logs and 'gateway.authentication' in logs and 'gateway.admission' in logs: break
        assert trace in logs and 'gateway.authentication' in logs and 'gateway.admission' in logs, 'Expected sampled policy spans absent'
        blocks = logs.split('ResourceSpans #')
        services = [name for name in ['gateway-data-plane', 'orders'] if
                    any(trace in block and 'Str(' + name + ')' in block for block in blocks)]
        assert len(services) == 2, ('Missing common trace across gateway/backend', services)
        assert canary not in logs, 'Query data leaked into collector'
        metrics = urllib.request.urlopen('http://localhost:49080/actuator/prometheus', timeout=10).read().decode()
        assert 'gateway_policy' in metrics and 'gateway_configuration' in metrics
        assert canary not in metrics and u not in metrics and key['secret'] not in metrics, 'Sensitive metric label'
        targets = \
        get_json('http://localhost:' + os.environ.get('PROMETHEUS_PORT', '49090') + '/api/v1/targets')['data'][
            'activeTargets']
        for _ in range(30):
            if len(targets) >= 3 and all(x['health'] == 'up' for x in targets): break
            time.sleep(1);
            targets = \
            get_json('http://localhost:' + os.environ.get('PROMETHEUS_PORT', '49090') + '/api/v1/targets')['data'][
                'activeTargets']
        assert len(targets) >= 3 and all(x['health'] == 'up' for x in targets), [(t['labels'], t['health']) for t in
                                                                                 targets]
        try:
            compose('stop', 'collector')
            statuses = [request('GET', 'http://localhost:48080/api/v1/orders', bearer=u)[0] for _ in range(20)]
            assert statuses == [200] * 20, statuses
        finally:
            compose('start', 'collector')
        all_logs = compose('logs', '--since', start, 'gateway', 'gateway2', 'control', 'orders', 'payments',
                           'inventory', 'collector')
        for secret in [a, u, key['secret']]: assert secret not in all_logs, 'Credential canary appeared in logs'
        result = {'passed': True, 'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'trace_id': trace, 'trace_services': services,
                  'policy_spans': ['gateway.authentication', 'gateway.admission'],
                  'prometheus_targets_up': len(targets), 'collector_outage_requests': 20,
                  'collector_outage_status': 200, 'credential_canaries_absent': True, 'query_canary_redacted': True,
                  'boundary': 'local Compose HTTP; traces exported to detailed debug collector, not a durable tracing backend'}
        (ROOT / 'results/observability.json').write_text(json.dumps(result, indent=2));
        print(json.dumps(result, indent=2))
    finally:
        assert request('DELETE', admin + 'keys/' + key['id'], bearer=a)[0] == 204


if __name__ == '__main__': main()
