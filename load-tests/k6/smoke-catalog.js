// Milestone 3 smoke test: does public browsing through the gateway work under light concurrent
// load without errors? This is NOT the Milestone 12 performance test; its numbers are not
// performance claims.
//
// Run (from the repository root, with the stack up via scripts/dev-up.sh --apps):
//   docker run --rm -i --network commerceflow_default -e BASE_URL=http://api-gateway:8080 \
//     grafana/k6:2.2.0 run - < load-tests/k6/smoke-catalog.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 10,
  duration: '1m',
  thresholds: {
    // Smoke criteria from ROADMAP.md, Milestone 3: no errors.
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
};

const SEARCHES = ['lamp', 'desk', 'mug', 'chair', ''];

export default function () {
  const q = SEARCHES[Math.floor(Math.random() * SEARCHES.length)];
  const list = http.get(`${BASE_URL}/api/v1/products?size=20${q ? `&q=${q}` : ''}`, {
    tags: { endpoint: 'list' },
  });
  check(list, {
    'list is 200': (r) => r.status === 200,
    'list has items array': (r) => Array.isArray(r.json('items')),
  });

  const items = list.status === 200 ? list.json('items') : [];
  if (items.length > 0) {
    const product = http.get(`${BASE_URL}/api/v1/products/${items[0].id}`, {
      tags: { endpoint: 'get' },
    });
    check(product, { 'product is 200': (r) => r.status === 200 });
  }

  // About two iterations per second per user keeps 10 users near 20-40 requests per second,
  // under the gateway's per-client limit, so this measures the service, not the rate limiter.
  sleep(0.5);
}
