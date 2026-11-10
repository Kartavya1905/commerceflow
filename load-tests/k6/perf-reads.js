// Milestone 12: the read workload behind NFR-05 (p95 < 300 ms for GET /products, GET /products/{id}
// and GET /orders/{id} at 500 virtual users). Run it through scripts/perf.sh, which seeds the data,
// records the environment, and keeps the raw summary.
//
// Each virtual user is one of the seeded customers and repeats: search or browse a category, open
// a product from the result, look at one of their own orders, then think for 0.5-1.5 s.
//
// PROFILE picks the shape:
//   smoke   5 users for 1 min: does it work at all
//   load    warm up to 500 users over 2 min, hold 500 for 5 min (measured), ramp down
//   stress  steps of 500, 750, 1,000, 1,500 and 2,000 users, 2 min each: where it breaks
//   soak    warm up to 250 users, hold 250 for 30 min: drift and leaks over time
//
// Every request is tagged with its phase (warmup, measure, rampdown) and, for stress, its step.
// Results are judged on the measure phase only, so a cold JVM never counts (the M12 gate), and
// reported as p50/p95/p99 and error rate, never averages.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://api-gateway:8080';
const PROFILE = __ENV.PROFILE || 'smoke';
const seeded = JSON.parse(open('/data/perf-users.json'));
const SEARCHES = ['lamp', 'desk', 'mug', 'chair', 'shelf', 'oak lamp', 'steel', 'basket'];
const CATEGORIES = ['lighting', 'furniture', 'kitchen', 'office', 'garden', 'bath', 'decor', 'storage'];
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const TOKEN_LIFETIME_MS = 10 * 60 * 1000; // access tokens last 15 minutes; sign in again at 10

const MIN = 60 * 1000;
// stages, and where the measured window starts and ends (ms from the start of the run)
const PROFILES = {
  smoke: { stages: [{ duration: '15s', target: 5 }, { duration: '1m', target: 5 }], measure: [15e3, 75e3] },
  load: {
    stages: [
      { duration: '2m', target: 500 },
      { duration: '5m', target: 500 },
      { duration: '30s', target: 0 },
    ],
    measure: [2 * MIN, 7 * MIN],
  },
  stress: {
    stages: [
      { duration: '1m', target: 500 }, { duration: '2m', target: 500 },
      { duration: '30s', target: 750 }, { duration: '2m', target: 750 },
      { duration: '30s', target: 1000 }, { duration: '2m', target: 1000 },
      { duration: '30s', target: 1500 }, { duration: '2m', target: 1500 },
      { duration: '30s', target: 2000 }, { duration: '2m', target: 2000 },
      { duration: '30s', target: 0 },
    ],
    measure: [1 * MIN, 13.5 * MIN],
    // Each step's held 2 minutes, after its 30 s ramp.
    steps: [[1, 3, 500], [3.5, 5.5, 750], [6, 8, 1000], [8.5, 10.5, 1500], [11, 13, 2000]],
  },
  soak: {
    stages: [
      { duration: '2m', target: 250 },
      { duration: '30m', target: 250 },
      { duration: '30s', target: 0 },
    ],
    measure: [2 * MIN, 32 * MIN],
  },
};
const profile = PROFILES[PROFILE];
if (!profile) throw new Error(`unknown PROFILE ${PROFILE}`);

const ENDPOINTS = ['list', 'product', 'order'];
const thresholds = {
  // NFR-05, judged on the measured window only.
  'http_req_duration{phase:measure,name:list}': ['p(95)<300'],
  'http_req_duration{phase:measure,name:product}': ['p(95)<300'],
  'http_req_duration{phase:measure,name:order}': ['p(95)<300'],
  'http_req_failed{phase:measure}': ['rate<0.01'],
  'http_req_failed{phase:warmup}': ['rate<1'],
  'http_req_duration{phase:warmup}': ['p(95)<60000'],
};
// One submetric per stress step, so the summary has each step's own percentiles and errors.
for (const [, , users] of profile.steps || []) {
  thresholds[`http_req_duration{step:${users}}`] = ['p(95)<1000'];
  thresholds[`http_req_failed{step:${users}}`] = ['rate<0.01'];
  for (const name of ENDPOINTS) thresholds[`http_req_duration{step:${users},name:${name}}`] = ['p(95)<1000'];
}

export const options = {
  scenarios: { reads: { executor: 'ramping-vus', startVUs: 0, stages: profile.stages, gracefulRampDown: '10s' } },
  thresholds,
  discardResponseBodies: false,
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

let token = null;
let tokenAt = 0;

function phaseTags() {
  const t = exec.instance.currentTestRunDuration;
  const tags = { phase: t < profile.measure[0] ? 'warmup' : t <= profile.measure[1] ? 'measure' : 'rampdown' };
  for (const [from, to, users] of profile.steps || []) {
    if (t >= from * MIN && t <= to * MIN) tags.step = String(users);
  }
  return tags;
}

export default function () {
  const user = seeded.users[(exec.vu.idInTest - 1) % seeded.users.length];
  if (!token || Date.now() - tokenAt > TOKEN_LIFETIME_MS) {
    const r = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email: user.email, password: user.password }),
      { headers: JSON_HEADERS, tags: { ...phaseTags(), name: 'login' } });
    if (r.status !== 200) {
      sleep(1);
      return;
    }
    token = r.json('accessToken');
    tokenAt = Date.now();
  }
  const auth = { Authorization: `Bearer ${token}` };

  const browse = Math.random() < 0.5
    ? `q=${encodeURIComponent(SEARCHES[Math.floor(Math.random() * SEARCHES.length)])}`
    : `category=${CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)]}`;
  const list = http.get(`${BASE_URL}/api/v1/products?${browse}&size=20`, { tags: { ...phaseTags(), name: 'list' } });
  check(list, { 'list 200': (r) => r.status === 200 });

  const items = list.status === 200 ? list.json('items') : [];
  if (items.length > 0) {
    const pick = items[Math.floor(Math.random() * items.length)];
    const product = http.get(`${BASE_URL}/api/v1/products/${pick.id}`, { tags: { ...phaseTags(), name: 'product' } });
    check(product, { 'product 200': (r) => r.status === 200 });
  }

  const orderId = user.orders[Math.floor(Math.random() * user.orders.length)];
  const order = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, { headers: auth, tags: { ...phaseTags(), name: 'order' } });
  check(order, { 'order 200': (r) => r.status === 200 });

  sleep(0.5 + Math.random());
}

export function handleSummary(data) {
  return {
    '/out/summary.json': JSON.stringify(data, null, 1),
    stdout: textSummary(data),
  };
}

// The measured window per endpoint, and each stress step: what the report quotes.
function textSummary(data) {
  const m = data.metrics;
  const fmt = (v) => (v === undefined ? '-' : `${v.toFixed(1)} ms`);
  const line = (label, key) => {
    const v = m[key] && m[key].values;
    return v ? `  ${label.padEnd(26)} p50 ${fmt(v['p(50)'])}  p95 ${fmt(v['p(95)'])}  p99 ${fmt(v['p(99)'])}  n=${v.count}\n` : '';
  };
  let out = `\nprofile ${PROFILE}, measured window ${profile.measure[0] / 1000}-${profile.measure[1] / 1000} s\n`;
  for (const name of ENDPOINTS) out += line(`${name} (measure)`, `http_req_duration{phase:measure,name:${name}}`);
  const failed = m['http_req_failed{phase:measure}'];
  if (failed) out += `  errors (measure)           ${(failed.values.rate * 100).toFixed(3)} %\n`;
  for (const [, , users] of profile.steps || []) {
    out += line(`all at ${users} users`, `http_req_duration{step:${users}}`);
    const f = m[`http_req_failed{step:${users}}`];
    if (f) out += `  errors at ${users} users`.padEnd(29) + `${(f.values.rate * 100).toFixed(3)} %\n`;
  }
  const reqs = m.http_reqs && m.http_reqs.values;
  if (reqs) out += `  requests                   ${reqs.count} (${reqs.rate.toFixed(1)}/s over the whole run)\n`;
  out += `  thresholds: ${Object.entries(m).filter(([, v]) => v.thresholds).map(([k, v]) =>
    `${k} ${Object.values(v.thresholds).every((t) => t.ok) ? 'ok' : 'FAILED'}`).join('; ')}\n`;
  return out;
}
