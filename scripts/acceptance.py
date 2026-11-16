#!/usr/bin/env python3
"""HTTP/upgrade acceptance against the local Compose stack; no mocks or softened success codes."""
import argparse
import concurrent.futures
import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

from demo import admin, request, token
from fixtures import BASE, converge, publish, register, route, unique, upsert

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / 'results';
RESULTS.mkdir(exist_ok=True)
records = []


def case(name, fn):
    start = time.monotonic()
    try:
        detail = fn();
        records.append({'case': name, 'passed': True, 'seconds': round(time.monotonic() - start, 3), 'detail': detail});
        print('PASS', name, detail or '', flush=True)
    except Exception as error:
        records.append({'case': name, 'passed': False, 'seconds': round(time.monotonic() - start, 3),
                        'error': type(error).__name__ + ': ' + str(error)[:1000]});
        raise
    finally:
        (RESULTS / 'acceptance.json').write_text(json.dumps(records, indent=2))


def http(method, path, bearer=None, body=None, headers=None, port=48080, key=None): return request(method,
                                                                                                   f'http://localhost:{port}' + path,
                                                                                                   body, bearer, key,
                                                                                                   headers)


def compose(*args, extra=None):
    env = os.environ.copy();
    env.update(extra or {})
    return subprocess.run(
        ['docker', 'compose', '--env-file', str(ROOT / 'docker/.env'), '-f', str(ROOT / 'docker/compose.yml'), *args],
        cwd=ROOT, env=env, check=True, capture_output=True, text=True).stdout


def image_provenance():
    fingerprint = subprocess.check_output(['python3', str(ROOT / 'scripts/source_fingerprint.py')], text=True).strip();
    images = {}
    for name in ['gateway', 'gateway2', 'control', 'orders', 'payments', 'inventory']:
        data = \
            json.loads(subprocess.check_output(['docker', 'inspect', 'enterprise-gateway-' + name + '-1'], text=True))[
                0]
        assert data['Config']['Labels'].get('org.opencontainers.image.revision') == fingerprint, (name, 'stale image')
        assert data['State']['Running'], name;
        images[name] = {'image_id': data['Image'], 'container_id': data['Id'], 'source': fingerprint}
    (RESULTS / 'images.json').write_text(json.dumps(images, indent=2));
    return {'source': fingerprint, 'services': len(images)}


def security():
    a = token('admin');
    u = token('demo')
    assert http('GET', '/api/v1/orders')[0] == 401
    assert http('GET', '/api/v1/orders', bearer=a)[0] == 401
    # Change only the signature; no unverified payload is used as identity.
    parts = u.split('.');
    parts[2] = ('A' if parts[2][0] != 'A' else 'B') + parts[2][1:]
    assert http('GET', '/api/v1/orders', bearer='.'.join(parts))[0] == 401
    assert request('GET', admin + 'apis')[0] == 401
    assert request('POST', admin + 'configurations/publish', bearer=u)[0] in (401, 403)
    status, body = http('GET', '/api/v1/orders', bearer=u,
                        headers={'X-Tenant-ID': 'forged', 'X-User-ID': 'forged', 'X-Gateway-Identity': 'forged',
                                 'X-Forwarded-For': '1.2.3.4'})
    assert status == 200 and body['tenant'] == 'demo', (status, body)
    api = unique('ssrf');
    upsert('apis', {'id': api, 'tenant': 'demo'}, a)
    for target in ['http://169.254.169.254', 'http://localhost:8081', 'http://127.0.0.1:6379',
                   'http://orders:8091@169.254.169.254']:
        status, _ = request('POST', admin + 'routes', route(api, upstreams=[target]), a);
        assert status == 400, (target, status)
    for path in ['/api/v1/orders/%2e%2e/payments', '/api/v1/orders//x', '/api/v1/orders;evil']:
        assert http('GET', path, bearer=u)[0] == 400, path
    # Raw framing ambiguity through the real Netty listener.
    with socket.create_connection(('localhost', 48080), timeout=5) as sock:
        sock.sendall(
            b'POST /api/v1/orders HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n');
        response = sock.recv(4096)
        assert b'400' in response.split(b'\r\n')[0], response[:100]
    assert http('POST', '/api/v1/orders', bearer=u, body={'payload': 'x' * 1048576})[0] == 413
    assert http('OPTIONS', '/api/v1/orders',
                headers={'Origin': 'https://evil.example', 'Access-Control-Request-Method': 'GET'})[0] == 403
    req = urllib.request.Request(BASE + '/api/v1/orders', method='OPTIONS',
                                 headers={'Origin': 'https://app.example.test', 'Access-Control-Request-Method': 'GET'})
    with urllib.request.urlopen(req, timeout=10) as response:
        assert response.headers['Access-Control-Allow-Origin'] == 'https://app.example.test'
    return 'JWT audience/signature, administration, identity spoofing, SSRF, path/framing, size and CORS'


def roles_and_tenants():
    a = token('admin');
    u = token('demo');
    name = unique('role');
    spec = register(route(name, roles=['finance']), a)
    other = unique('tenant');
    other_spec = register(route(other, tenant='other'), a)
    publish(a);
    converge('/api/v1/' + name, u, expected=403);
    converge('/api/v1/' + other, u, expected=403)
    consumer = unique('finance');
    upsert('consumers', {'id': consumer, 'tenant': 'demo'}, a)
    status, issued = request('POST', admin + 'consumers/' + consumer + '/keys',
                             {'scopes': ['orders:read'], 'roles': ['finance'], 'days': 1}, a);
    assert status == 201
    try:
        assert http('GET', '/api/v1/' + name, key=issued['secret'])[0] == 200
        assert http('GET', '/api/v1/' + other, key=issued['secret'], headers={'X-Tenant-ID': 'other'})[0] == 403
        status, metadata = request('GET', admin + 'consumers/' + consumer + '/keys', bearer=a);
        assert status == 200 and metadata
        assert all(not ({'secret', 'verifier', 'registry_value'} & set(row)) for row in metadata)
        status, _ = request('PATCH', admin + 'consumers/' + consumer, {'id': consumer, 'tenant': 'other'}, a,
                            extra={'If-Match': '1'});
        assert status == 400
        status, _ = request('DELETE', admin + 'consumers/' + consumer, bearer=a, extra={'If-Match': '99'});
        assert status == 412
        assert http('GET', '/api/v1/' + name, key=issued['secret'])[0] == 200
        assert request('DELETE', admin + 'consumers/' + consumer, bearer=a, extra={'If-Match': '1'})[0] == 204
        assert http('GET', '/api/v1/' + name, key=issued['secret'])[0] == 401
        assert \
            request('POST', admin + 'consumers/' + consumer + '/keys',
                    {'scopes': ['orders:read'], 'roles': [], 'days': 1},
                    a)[0] == 404
    finally:
        request('DELETE', admin + 'keys/' + issued['id'], bearer=a)
    return 'role AND scope AND verified tenant; metadata redaction; optimistic deletion and key revocation'


def named_policy_and_adjustment():
    a = token('admin');
    u = token('demo');
    name = unique('plan')
    upsert('rate-limits', {'id': name, 'tenant': 'demo', 'rate': 2, 'burst': 2}, a)
    upsert('quotas', {'id': name, 'tenant': 'demo', 'dailyQuota': 5, 'monthlyQuota': 5}, a)
    upsert('plans', {'id': name, 'tenant': 'demo', 'rate': 1, 'burst': 1, 'dailyQuota': 3, 'monthlyQuota': 3}, a)
    spec = register(route(name, rateLimitPolicyId=name, quotaPolicyId=name, planId=name), a);
    snapshot = publish(a)
    compiled = next(row for row in snapshot['routes'] if row['id'] == name)
    assert [compiled[x] for x in ['rate', 'burst', 'dailyQuota', 'monthlyQuota']] == [1, 1, 3, 3], compiled
    time.sleep(4)
    for i in range(3): assert http('GET', '/api/v1/' + name, bearer=u)[0] == 200;time.sleep(1.1)
    assert http('GET', '/api/v1/' + name, bearer=u)[0] == 429
    code, usage = request('GET', admin + 'consumers/demo-client/usage', bearer=a);
    assert code == 200
    revision = usage['tenantApiUsage'][name]['revision'];
    body = {'dailyUsed': 1, 'monthlyUsed': 1, 'idempotencyKey': unique('adjust'), 'reason': 'acceptance credit'}
    path = admin + 'quotas/demo/' + name + '/adjustments'
    assert request('POST', path, body, a, extra={'If-Match': revision})[0] == 200
    assert request('POST', path, body, a, extra={'If-Match': revision})[0] == 200
    _, usage = request('GET', admin + 'consumers/demo-client/usage', bearer=a);
    state = usage['tenantApiUsage'][name];
    assert int(state['d']) == 1 and int(state['m']) == 1 and int(state['revision']) == int(revision) + 1, state
    assert request('POST', path, {**body, 'idempotencyKey': unique('stale')}, a, extra={'If-Match': revision})[0] == 412
    assert request('POST', admin + 'quotas/demo/' + name + '/reset',
                   {**body, 'dailyUsed': 0, 'monthlyUsed': 0, 'idempotencyKey': unique('reset')}, a,
                   extra={'If-Match': state['revision']})[0] == 200
    return 'named policies resolve at publish; plan ceilings; admitted quota; audited idempotent CAS corrections/reset'


def routing():
    a = token('admin');
    u = token('demo');
    name = unique('routing');
    spec = register(route(name, upstreams=['http://orders:8091'] * 3 + ['http://inventory:8093']), a)
    upsert('routes', {**spec, 'id': name + 'v2', 'path': '/api/v2/' + name + '/**'}, a)
    upsert('routes', {**spec, 'id': name + 'header', 'priority': -1, 'headers': {'X-Release': 'canary'},
                      'upstreams': ['http://inventory:8093']}, a)
    host_name = unique('host');
    host_spec = register(route(host_name, host='api.example.test'), a)
    publish(a);
    converge('/api/v1/' + name, u)
    seen = set()
    for _ in range(24):
        status, body = http('GET', '/api/v1/' + name, bearer=u);
        assert status == 200;
        seen.add(body['service'])
    assert seen == {'orders', 'inventory'}, seen
    status, body = http('GET', '/api/v1/' + name, bearer=u, headers={'X-Release': 'canary'});
    assert status == 200 and body['service'] == 'inventory'
    with urllib.request.urlopen(
            urllib.request.Request(BASE + '/api/v2/' + name, headers={'Authorization': 'Bearer ' + u}),
            timeout=10) as response: assert response.headers['X-API-Version'] == 'v2'
    assert http('GET', '/api/v1/' + host_name, bearer=u)[0] == 404
    assert http('GET', '/api/v1/' + host_name, bearer=u, headers={'Host': 'api.example.test'})[0] == 200
    assert http('DELETE', '/api/v1/' + host_name, bearer=u, headers={'Host': 'api.example.test'})[0] == 404
    assert request('DELETE', admin + 'apis/' + host_name, bearer=a, extra={'If-Match': '1'})[0] == 204
    publish(a);
    converge('/api/v1/' + host_name, u, expected=404)
    return 'URL versions, host/method/header predicates, weighted endpoints/canary and API deactivation'


def burst_and_resilience():
    a = token('admin');
    u = token('demo');
    name = unique('burst');
    spec = register(route(name, rate=1, burst=3), a)
    retry = register(route('acceptance-read-retry', path='/api/v1/resilience/**', retries=1), a)
    slow = register(
        route('acceptance-slow', path='/api/v1/orders/**', methods=['POST'], priority=-10, timeoutMs=200,
              concurrency=1),
        a)
    publish(a);
    time.sleep(4)
    started = time.monotonic()

    def call(i): return http('GET', '/api/v1/' + name, bearer=u, port=48080 if i % 2 == 0 else 48082)[0]

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: statuses = list(pool.map(call, range(20)))
    elapsed = time.monotonic() - started
    burst_successes = statuses.count(200)
    assert statuses.count(200) <= 3 + int(elapsed) + 1, statuses;
    assert 429 in statuses, statuses;
    assert all(x in (200, 429) for x in statuses), statuses
    key = unique('flaky');
    status, body = http('GET', '/api/v1/resilience/' + key, bearer=u);
    assert status == 200 and body['attempts'] == 2, (status, body)
    assert request('POST', admin + 'routes', route(unique('unsafe'), methods=['POST'], retries=1), a)[0] == 400
    # One dispatch despite timeout; local bulkhead rejects concurrent work without queuing.
    before = http('GET', '/api/v1/payments/diagnostics', bearer=u)[1]['attempts']
    headers = {'Idempotency-Key': unique('timeout')}
    status, _ = http('POST', '/api/v1/payments?delay=5000', bearer=u, body={'amount': 1}, headers=headers)
    assert status in (503, 504), status
    after = http('GET', '/api/v1/payments/diagnostics', bearer=u)[1]['attempts'];
    assert after - before == 1, (before, after)

    def post(i): return http('POST', '/api/v1/orders?delay=1000', bearer=u, body={'item': i})[0]

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: statuses = list(pool.map(post, range(8)))
    assert 429 in statuses and all(x in (429, 503, 504) for x in statuses), statuses
    # Undo the test's higher-precedence route.
    assert request('DELETE', admin + 'routes/' + slow['id'], bearer=a, extra={'If-Match': '1'})[0] == 204
    publish(a)
    return {'burst_successes': burst_successes, 'unsafe_dispatches': after - before, 'bulkhead_statuses': statuses}


def circuit_recovery():
    a = token('admin');
    u = token('demo')
    name = unique('breaker')
    spec = register(route(name, apiId='payments', path='/api/v1/payments/**', methods=['POST'],
                          upstreams=['http://payments:8092'], scopes=['payments:write'], priority=-20,
                          dailyQuota=10000, monthlyQuota=100000), a)
    publish(a);
    time.sleep(4)
    before = http('GET', '/api/v1/payments/diagnostics', bearer=u)[1]['attempts']
    try:
        for i in range(20):
            assert http('POST', '/api/v1/payments?fail=true', bearer=u, body={'amount': 1},
                        headers={'Idempotency-Key': unique('fail')})[0] == 503
        dispatched = http('GET', '/api/v1/payments/diagnostics', bearer=u)[1]['attempts'] - before
        assert dispatched == 20, dispatched
        assert http('POST', '/api/v1/payments', bearer=u, body={'amount': 1},
                    headers={'Idempotency-Key': unique('open')})[0] == 503
        assert http('GET', '/api/v1/payments/diagnostics', bearer=u)[1]['attempts'] == before + 20
        time.sleep(11)
        for i in range(10):
            status, _ = http('POST', '/api/v1/payments', bearer=u, body={'amount': 1},
                             headers={'Idempotency-Key': unique('probe')})
            assert status == 200, status
        return {'failed_dispatches': dispatched, 'open_probe_dispatches': 0, 'recovery_probes': 10}
    finally:
        status, rows = request('GET', admin + 'routes/' + name, bearer=a)
        if status == 200:
            assert request('DELETE', admin + 'routes/' + name, bearer=a,
                           extra={'If-Match': str(rows['revision'])})[0] == 204
            publish(a)


def websocket_and_stream():
    a = token('admin');
    u = token('demo')
    register(route('events-ws', apiId='events', path='/api/v1/events/echo/**', upstreams=['ws://orders:8091']), a)
    register(route('events-stream', apiId='events', path='/api/v1/events/stream/**'), a)
    publish(a);
    time.sleep(5)
    java = os.environ.get('JAVA_HOME', '') + '/bin/java' if os.environ.get('JAVA_HOME') else 'java'
    env = os.environ.copy();
    env['TOKEN'] = u
    result = subprocess.run([java, str(ROOT / 'scripts/WebSocketProbe.java')], env=env, text=True, capture_output=True,
                            timeout=60)
    assert result.returncode == 0, result.stdout + result.stderr
    started = time.monotonic()
    with urllib.request.urlopen(
            urllib.request.Request(BASE + '/api/v1/events/stream', headers={'Authorization': 'Bearer ' + u}),
            timeout=10) as response:
        first = response.read(16);
        first_at = time.monotonic() - started;
        rest = response.read();
        assert (first + rest).count(b'data:') == 10
    assert first_at < .9, first_at
    return {'websocket': 'authenticated echo and four-connection cap', 'first_stream_bytes_seconds': round(first_at, 3)}


def faults():
    a = token('admin');
    u = token('demo')
    # Use the second gateway's explicit test-only freshness setting; production default remains 300s.
    compose('up', '-d', '--no-build', '--no-deps', 'gateway2', extra={'SECONDARY_MAX_STALE_SECONDS': '8'})
    converge('/api/v1/orders', u)
    try:
        compose('stop', 'control');
        time.sleep(10)
        assert http('GET', '/api/v1/orders', bearer=u, port=48082)[0] == 503
        compose('restart', 'gateway');
        converge('/api/v1/orders', u, ports=(48080,))
    finally:
        compose('start', 'control');
        compose('up', '-d', '--no-build', '--no-deps', 'gateway2')
        for _ in range(60):
            try:
                if request('GET', admin + 'apis', bearer=a)[0] == 200: break
            except(OSError, ConnectionError):
                pass
            time.sleep(1)
        else:
            raise AssertionError('control failed to recover')
    converge('/api/v1/orders', u)
    # Reconciliation skips intermediate versions while a gateway is stopped.
    compose('stop', 'gateway2')
    try:
        first = publish(a)['version'];
        last = publish(a)['version'];
        assert last > first
    finally:
        compose('start', 'gateway2')
    converge('/api/v1/orders', u)
    # Coordinator outage: both JWT and keys must fail closed, then recover.
    status, key = request('POST', admin + 'consumers/demo-client/keys',
                          {'scopes': ['orders:read'], 'roles': [], 'days': 1}, a);
    assert status == 201
    try:
        compose('stop', 'redis')
        assert http('GET', '/api/v1/orders', bearer=u)[0] == 503
        assert http('GET', '/api/v1/orders', key=key['secret'])[0] == 503
    finally:
        compose('start', 'redis')
    converge('/api/v1/orders', u)
    assert http('GET', '/api/v1/orders', key=key['secret'])[0] == 200
    assert request('DELETE', admin + 'keys/' + key['id'], bearer=a)[0] == 204
    # Cached JWKS permit known unexpired tokens while the IdP is down.
    u = token('demo')
    assert http('GET', '/api/v1/orders', bearer=u)[0] == 200
    try:
        compose('stop', 'keycloak');
        assert http('GET', '/api/v1/orders', bearer=u)[0] == 200
    finally:
        compose('start', 'keycloak')
    return 'cold cache recovery, finite stale denial, missed-version reconciliation, Redis fail-closed/recovery and cached-JWKS outage'


def invalid_attempts():
    u = token('demo');
    statuses = [http('GET', '/api/v1/orders', bearer='invalid')[0] for _ in range(35)]
    assert 429 in statuses and all(x in (401, 429) for x in statuses), statuses
    assert http('GET', '/api/v1/orders', bearer=u)[0] == 200
    return {'unauthorized': statuses.count(401), 'throttled': statuses.count(429)}


def cleanup_interrupted_fixtures():
    # Remove only this suite's historical temporary routes after an interrupted run.
    cleanup_token = token('admin')
    status, rows = request('GET', admin + 'routes', bearer=cleanup_token)
    assert status == 200
    import re
    for item in rows:
        body = item['body'];
        identifier = item['id']
        if identifier == 'acceptance-slow' or (
                re.fullmatch(r'breaker[a-f0-9]{10}', identifier) and body['path'] == '/api/v1/payments/**' and body.get(
                'priority') == -20) or (
                re.fullmatch(r'readretry[a-f0-9]{10}', identifier) and body['path'] == '/api/v1/resilience/**') or (
                re.fullmatch(r'slow[a-f0-9]{10}', identifier) and body.get('priority') == -10 and body[
            'path'] == '/api/v1/orders/**'):
            assert request('DELETE', admin + 'routes/' + identifier, bearer=cleanup_token,
                           extra={'If-Match': str(item['revision'])})[0] == 204


if __name__ == '__main__':
    parser = argparse.ArgumentParser();
    parser.add_argument('--faults', action='store_true');
    args = parser.parse_args()
    cleanup_interrupted_fixtures()
    case('final image provenance', image_provenance)
    converge('/api/v1/orders', token('demo'))
    for name, fn in [('adversarial HTTP boundaries', security), ('role and tenant isolation', roles_and_tenants),
                     ('named policies, plans and quota correction', named_policy_and_adjustment),
                     ('routing, versioning and deactivation', routing),
                     ('burst, safe retry, deadline and bulkhead', burst_and_resilience),
                     ('circuit opening and recovery', circuit_recovery),
                     ('WebSocket and streaming', websocket_and_stream)]: case(name, fn)
    if args.faults: case('dependency and configuration recovery', faults)
    case('distributed invalid-credential throttling', invalid_attempts)
    print('PASS all requested acceptance groups', flush=True)
