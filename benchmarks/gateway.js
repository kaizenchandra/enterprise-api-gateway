import http from 'k6/http';
import {check} from 'k6';
import {Counter, Trend} from 'k6/metrics';

const rps = Number(__ENV.RPS || 100);
const policy = new Trend('gateway_policy_ms', true);
const accepted = new Counter('admitted_responses');
const statuses = new Counter('response_status');
export const options = {
    scenarios: {
        steady: {
            executor: 'constant-arrival-rate',
            rate: rps,
            timeUnit: '1s',
            duration: __ENV.DURATION || '15s',
            preAllocatedVUs: 32,
            maxVUs: Number(__ENV.MAX_VUS || 128)
        }
    }, thresholds: {http_req_failed: ['rate==0']}, summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max']
};
export default function () {
    const headers = __ENV.API_KEY ? {'X-API-Key': __ENV.API_KEY} : {Authorization: 'Bearer ' + __ENV.TOKEN};
    const r = http.get((__ENV.BASE_URL || 'http://localhost:48080') + (__ENV.BENCH_PATH || '/api/v1/orders'), {
        headers,
        tags: {name: 'gateway-benchmark'}
    });
    statuses.add(1, {status: String(r.status)});
    if (r.status === 200) accepted.add(1);
    const match = (r.headers['Server-Timing'] || '').match(/gateway-policy;dur=([\d.]+)/);
    if (match) policy.add(Number(match[1]));
    check(r, {'HTTP 200': response => response.status === 200});
}

export function handleSummary(data) {
    return {
        [__ENV.SUMMARY_FILE || 'results/benchmark.json']: JSON.stringify(data, null, 2),
        stdout: JSON.stringify({
            offeredRps: rps,
            requests: data.metrics.http_reqs,
            latency: data.metrics.http_req_duration,
            policy: data.metrics.gateway_policy_ms,
            failed: data.metrics.http_req_failed,
            dropped: data.metrics.dropped_iterations
        }) + '\n'
    };
}
