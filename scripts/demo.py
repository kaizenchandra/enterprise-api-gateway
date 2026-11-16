#!/usr/bin/env python3
import json
import os
import time
import urllib.parse
import urllib.request

admin = 'http://localhost:48081/api/v1/admin/'


def token(client):
    data = urllib.parse.urlencode({'grant_type': 'client_credentials', 'client_id': client,
                                   'client_secret': 'local-' + client + '-secret'}).encode()
    for attempt in range(300):
        try:
            return json.load(
                urllib.request.urlopen('http://localhost:48180/realms/gateway/protocol/openid-connect/token', data,
                                       timeout=5))['access_token']
        except urllib.error.HTTPError as e:
            if e.code not in (404, 502, 503): raise
        except (OSError, ConnectionError):
            pass
        time.sleep(1)
    raise RuntimeError('Identity provider did not become ready')


def request(method, url, body=None, bearer=None, key=None, extra=None):
    headers = {'Content-Type': 'application/json', **(extra or {})}
    if bearer: headers['Authorization'] = 'Bearer ' + bearer
    if key: headers['X-API-Key'] = key
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, method=method,
                                 headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30 if url.startswith(admin) else 10) as r:
            return r.status, json.loads(r.read() or '{}')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


if __name__ == '__main__':
    a = token('admin');
    user = token('demo')
    for attempt in range(300):
        try:
            with urllib.request.urlopen('http://localhost:48081/', timeout=5) as response:
                if response.status == 200: break
        except (OSError, ConnectionError):
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Control plane did not become ready')
    a = token('admin');
    user = token('demo')
    for name in ['orders', 'payments', 'inventory']:
        request('POST', admin + 'apis', {'id': name}, a)
        route = {'id': name, 'apiId': name, 'tenant': 'demo', 'path': '/api/v1/' + name + '/**', 'host': None,
                 'methods': ['GET'], 'upstreams': [
                'http://' + name + ':' + str({'orders': 8091, 'payments': 8092, 'inventory': 8093}[name])], 'scopes': [
                {'orders': 'orders:read', 'payments': 'payments:write', 'inventory': 'inventory:read'}[name]],
                 'roles': [], 'priority': 0, 'rate': 100, 'burst': 100, 'dailyQuota': 10000, 'monthlyQuota': 100000,
                 'timeoutMs': 3000, 'concurrency': 32, 'enabled': True}
        code, result = request('POST', admin + 'routes', route, a);
        assert code in (201, 409), (code, result)
        if name in ('orders', 'payments', 'inventory'):
            route = {**route, 'id': name + '-write', 'methods': ['POST'], 'scopes': [name + ':write']}
            code, result = request('POST', admin + 'routes', route, a);
            assert code in (201, 409), (code, result)
    request('POST', admin + 'consumers', {'id': 'demo-client', 'tenant': 'demo'}, a)
    code, key = request('POST', admin + 'consumers/demo-client/keys',
                        {'scopes': ['orders:read'], 'roles': [], 'days': 1}, a);
    assert code == 201, (code, key)
    assert request('POST', admin + 'configurations/publish', bearer=a)[0] == 200
    time.sleep(6)
    for attempt in range(12):
        try:
            if request('GET', 'http://localhost:48080/api/v1/orders', bearer=user)[0] == 200: break
        except (OSError, ConnectionError):
            pass
        time.sleep(2)
    else:
        raise RuntimeError('Protected route did not converge and forward successfully')
    assert request('GET', 'http://localhost:48080/api/v1/orders', bearer=user)[0] == 200
    assert request('GET', 'http://localhost:48082/api/v1/orders', key=key['secret'])[0] == 200
    assert request('GET', 'http://localhost:48080/api/v1/orders')[0] == 401
    assert request('GET', 'http://localhost:48080/api/v1/orders', bearer=a)[0] == 401
    assert request('GET', 'http://localhost:48080/api/v1/orders', bearer=user, extra={'X-Tenant-ID': 'evil'})[1][
               'tenant'] == 'demo'
    assert request('POST', admin + 'configurations/publish', bearer=user)[0] in (401, 403)
    assert request('POST', 'http://localhost:48080/api/v1/orders', {}, key=key['secret'])[0] == 403
    code, rotated = request('POST', admin + 'keys/' + key['id'] + '/rotate',
                            {'scopes': ['orders:read'], 'roles': [], 'days': 1}, a)
    assert code == 200, (code, rotated)
    assert request('GET', 'http://localhost:48080/api/v1/orders', key=key['secret'])[0] == 401
    result = request('GET', 'http://localhost:48082/api/v1/orders', key=rotated['secret']);
    assert result[0] == 200, ('rotated key use failed', result[0])
    assert request('DELETE', admin + 'keys/' + rotated['id'], bearer=a)[0] == 204
    assert request('GET', 'http://localhost:48080/api/v1/orders', key=rotated['secret'])[0] == 401
    code, order = request('POST', 'http://localhost:48080/api/v1/orders', {'item': 'book'}, user);
    assert code == 200, (code, order)
    assert request('GET', 'http://localhost:48080/api/v1/orders/' + order['id'], bearer=user)[0] == 200
    headers = {'Idempotency-Key': 'demo-payment-' + str(time.time_ns())}
    code, payment = request('POST', 'http://localhost:48080/api/v1/payments', {'amount': 100}, user, extra=headers);
    assert code == 200, (code, payment)
    assert request('POST', 'http://localhost:48080/api/v1/payments', {'amount': 100}, user, extra=headers)[1]['id'] == \
           payment['id']
    assert request('POST', 'http://localhost:48080/api/v1/payments', {'amount': 101}, user, extra=headers)[0] == 409
    assert request('GET', 'http://localhost:48080/api/v1/inventory', bearer=user)[0] == 200
    code, inventory_key = request('POST', admin + 'consumers/demo-client/keys',
                                  {'scopes': ['inventory:write'], 'roles': [], 'days': 1}, a)
    assert code == 201, ('inventory key issuance failed', code)
    try:
        assert request('POST', 'http://localhost:48080/api/v1/inventory/reservations', {}, key=inventory_key['secret'])[
                   0] == 200
    finally:
        assert request('DELETE', admin + 'keys/' + inventory_key['id'], bearer=a)[0] == 204
    assert request('GET', admin + 'apis')[0] == 401
    assert request('GET', 'http://localhost:48080/api/v1/orders', key=key['secret'])[0] == 401
    print(
        'PASS routing on two gateways, JWT audience, API key issue/revoke, forged tenant, admin isolation, key rotation, orders, inventory, idempotent payment')
