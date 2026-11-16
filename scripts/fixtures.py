import time
import uuid

from demo import request, admin

BASE = 'http://localhost:48080'


def unique(prefix): return prefix + uuid.uuid4().hex[:10]


def upsert(kind, body, bearer):
    status, result = request('POST', admin + kind, body, bearer)
    if status == 409:
        status, current = request('GET', admin + kind + '/' + body['id'], bearer=bearer);
        assert status == 200, (kind, status)
        status, result = request('PATCH', admin + kind + '/' + body['id'], body, bearer,
                                 extra={'If-Match': str(current['revision'])});
        assert status == 200, (kind, status)
    else:
        assert status == 201, (kind, status, result)
    return body


def route(name, **overrides):
    return {'id': name, 'apiId': name, 'tenant': 'demo', 'path': '/api/v1/' + name + '/**', 'host': None,
            'methods': ['GET'], 'upstreams': ['http://orders:8091'], 'scopes': ['orders:read'], 'roles': [],
            'priority': 0, 'rate': 1000, 'burst': 1000, 'dailyQuota': 100000, 'monthlyQuota': 1000000,
            'timeoutMs': 3000, 'concurrency': 32, 'enabled': True, 'retries': 0, 'headers': {}, **overrides}


def register(spec, bearer):
    upsert('apis', {'id': spec['apiId'], 'tenant': spec['tenant'], 'enabled': True}, bearer)
    upsert('routes', spec, bearer)
    return spec


def publish(bearer):
    status, snapshot = request('POST', admin + 'configurations/publish', bearer=bearer);
    assert status == 200, (status, snapshot);
    return snapshot


def converge(path, bearer, ports=(48080, 48082), expected=200):
    for port in ports:
        last = None
        for attempt in range(90):
            try:
                last = request('GET', f'http://localhost:{port}' + path, bearer=bearer)
                if last[0] == expected: break
            except (OSError, ConnectionError):
                pass
            time.sleep(.5)
        else:
            raise AssertionError(('convergence failed', port, path, last))
