// Milestone 10 HPA experiment: a fixed rate of catalogue reads on catalog-service, from inside the
// cluster, so the autoscaler has CPU to react to. scripts/kind-hpa.sh runs it as a Job and records
// what the HorizontalPodAutoscaler does; docs/reports/hpa-experiment.md has the results.
//
// It calls catalog-service directly (http://catalog-service:8082), not through the gateway: the
// gateway's per-client rate limit would cap the load first, and this measures the autoscaler.
// The rate is fixed (requests per second, not users), so a run is repeatable: k6 starts as many
// users as it needs to keep it, and reports if it could not.
// Not a performance test; its numbers are not performance claims.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://catalog-service:8082';
const RATE = Number(__ENV.RATE || 200);
const SEARCHES = ['lamp', 'desk', 'mug', 'chair', ''];

export const options = {
  scenarios: {
    reads: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '30s', target: RATE },
        { duration: __ENV.HOLD || '4m', target: RATE },
        { duration: '15s', target: 0 },
      ],
    },
  },
  // The bodies are not needed, and keeping them is what fills the load generator's memory.
  discardResponseBodies: true,
  thresholds: {
    // Pods starting and stopping must not cost requests.
    http_req_failed: ['rate<0.001'],
    // k6 kept the rate (it drops iterations when it runs out of users).
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const q = SEARCHES[Math.floor(Math.random() * SEARCHES.length)];
  const r = http.get(`${BASE_URL}/api/v1/products?size=20${q ? `&q=${q}` : ''}`, {
    tags: { name: 'list' },
  });
  check(r, { 'list is 200': (res) => res.status === 200 });
}
