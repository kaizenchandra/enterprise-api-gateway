#!/usr/bin/env python3
"""Reproducible local load with raw metrics/resource evidence; no target-scale capacity claim."""
import datetime
import json
import os
import platform
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

from demo import token
from fixtures import converge, publish, register, route, unique

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / 'results'
SERVICES = ['enterprise-gateway-gateway-1', 'enterprise-gateway-redis-1', 'enterprise-gateway-orders-1',
            'enterprise-gateway-control-1']


def metrics(path):
    data = urllib.request.urlopen('http://localhost:49080/actuator/prometheus', timeout=5).read()
    path.write_bytes(data)


def redis_stats():
    return subprocess.check_output(
        ['docker', 'exec', 'enterprise-gateway-redis-1', 'redis-cli', 'info', 'commandstats'], text=True, timeout=5)


def monitor(stop, samples):
    while not stop.is_set():
        started = time.monotonic()
        try:
            output = subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *SERVICES],
                                             text=True, timeout=10)
            samples.append({'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                            'containers': [json.loads(line) for line in output.splitlines()]})
        except Exception as e:
            samples.append({'error': type(e).__name__})
        stop.wait(max(0, 3 - (time.monotonic() - started)))


def main():
    RESULTS.mkdir(exist_ok=True);
    a = token('admin');
    u = token('demo');
    name = unique('bench')
    register(route(name, rate=1000000, burst=1000000, dailyQuota=1000000000, monthlyQuota=1000000000, concurrency=512),
             a)
    publish(a);
    converge('/api/v1/' + name, u)
    runs = []
    for rps in [100, 1000]:
        env = os.environ.copy();
        env.update(TOKEN=token('demo'), RPS=str(rps), BENCH_PATH='/api/v1/' + name, MAX_VUS='128', DURATION='15s',
                   SUMMARY_FILE=str(RESULTS / f'k6-{rps}.json'))
        metrics(RESULTS / f'benchmark-{rps}-before.prom');
        (RESULTS / f'benchmark-{rps}-redis-before.txt').write_text(redis_stats())
        stop = threading.Event();
        samples = [];
        thread = threading.Thread(target=monitor, args=(stop, samples), daemon=True);
        thread.start()
        try:
            output = subprocess.run(['k6', 'run', str(ROOT / 'benchmarks/gateway.js')], env=env, cwd=ROOT, text=True,
                                    capture_output=True)
        finally:
            stop.set();thread.join(timeout=12)
        metrics(RESULTS / f'benchmark-{rps}-after.prom');
        (RESULTS / f'benchmark-{rps}-redis-after.txt').write_text(redis_stats())
        (RESULTS / f'benchmark-{rps}-resources.json').write_text(json.dumps(samples, indent=2))
        (RESULTS / f'k6-{rps}.log').write_text(output.stdout + output.stderr)
        data = json.loads((RESULTS / f'k6-{rps}.json').read_text());
        selected = ['http_reqs', 'http_req_duration', 'gateway_policy_ms', 'http_req_failed', 'dropped_iterations',
                    'admitted_responses', 'response_status', 'data_received', 'data_sent']
        runs.append({'offered_rps': rps, 'exit_code': output.returncode,
                     'metrics': {k: v for k, v in data['metrics'].items() if k in selected}})
    result = {'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'source': subprocess.check_output(['python3', str(ROOT / 'scripts/source_fingerprint.py')],
                                                text=True).strip(), 'route': name, 'host': platform.platform(),
              'protocol': 'HTTP/1.1, no TLS, Docker loopback, warmed JWT + RSA backend assertion + atomic Redis admission',
              'generator': 'one k6 process; 32 allocated VUs, maximum 128; dropped iterations reported',
              'telemetry': 'monitoring profile enabled; gateway sampling 0.1; Prometheus scraping; 2 active processors per application JVM',
              'resource_evidence': 'Prometheus snapshots include JVM heap/GC, pool/Redis policy latency and configuration age; Docker stats sampled every ~3s; Redis commandstats before/after. Not per-event-loop CPU profiling.',
              'measurement_boundary': 'gateway_policy_ms covers policy admission/signing only; http_req_duration is end-to-end. Full Gateway-only overhead not established.',
              'runs': runs}
    (RESULTS / 'benchmark-summary.json').write_text(json.dumps(result, indent=2));
    print(json.dumps(result, indent=2))
    if any(run['exit_code'] for run in runs): raise SystemExit(1)


if __name__ == '__main__': main()
