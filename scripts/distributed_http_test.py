#!/usr/bin/env python3
import concurrent.futures
import time

from demo import request, token, admin

if __name__ == '__main__':
    a = token('admin');
    user = token('demo');
    suffix = str(int(time.time()));
    api = 'limits' + suffix
    route = {'id': api, 'apiId': api, 'tenant': 'demo', 'path': '/api/v1/' + api + '/**', 'host': None,
             'methods': ['GET'], 'upstreams': ['http://orders:8091'], 'scopes': ['orders:read'], 'roles': [],
             'priority': 0, 'rate': 100, 'burst': 100, 'dailyQuota': 5, 'monthlyQuota': 5, 'timeoutMs': 3000,
             'concurrency': 32, 'enabled': True}
    assert request('POST', admin + 'apis', {'id': api, 'tenant': 'demo'}, a)[0] == 201
    assert request('POST', admin + 'routes', route, a)[0] == 201
    before = request('POST', admin + 'configurations/publish', bearer=a)[1]['version'];
    time.sleep(7)


    def call(i): return \
        request('GET', 'http://localhost:' + ('48080' if i % 2 == 0 else '48082') + '/api/v1/' + api, bearer=user)[0]


    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool: statuses = list(pool.map(call, range(40)))
    assert 0 <= statuses.count(200) <= 5, statuses
    assert all(s in (200, 429, 503) for s in statuses), statuses
    code, usage = request('GET', admin + 'consumers/demo-client/usage', bearer=a)
    assert code == 200, ('usage inspection failed', code)
    counters = usage['tenantApiUsage'][api]
    assert int(counters['d']) == 5 and int(counters['m']) == 5, counters
    print('Observed counters: exactly 5 admitted; HTTP successes:', statuses.count(200), 'quota denials:',
          statuses.count(429), 'dependency/upstream failures:', statuses.count(503))


    # Revision races: only one replacement of revision 1 may succeed.
    def edit(i): return request('PATCH', admin + 'routes/' + api, route, a, extra={'If-Match': '1'})[0]


    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: statuses = list(pool.map(edit, range(2)))
    assert sorted(statuses) == [200, 412], statuses
    # Conflicts never activate.
    conflict = {**route, 'id': api + '-conflict'}
    assert request('POST', admin + 'routes', conflict, a)[0] == 201
    assert request('POST', admin + 'configurations/publish', bearer=a)[0] == 400
    assert request('DELETE', admin + 'routes/' + api + '-conflict', bearer=a, extra={'If-Match': '1'})[0] == 204
    rolled = request('POST', admin + 'configurations/' + str(before) + '/rollback', bearer=a)
    assert rolled[0] == 200 and rolled[1]['version'] > before, rolled
    print(
        'PASS HTTP: authoritative counters admit 5/40 across two gateways, revision race, invalid publication, monotonic rollback')
