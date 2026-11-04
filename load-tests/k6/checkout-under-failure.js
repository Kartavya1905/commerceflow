// Milestone 10: checkouts keep working while pods fail (scripts/kind-chaos.sh makes them fail).
// Not a performance test; its numbers are not performance claims.
//
// Each checkout is sent with its own Idempotency-Key and, like the web app, retried with the same
// key when the answer is lost (a timeout from a hung pod, or a 5xx). Afterwards:
// - every checkout was accepted (202, or 200 for a retry of one already accepted);
// - the customer has exactly as many orders as checkouts: no retry created a second order;
// - every order reached CONFIRMED (stock is plentiful and the card always succeeds).
//
// Run it through scripts/kind-chaos.sh, which supplies BASE_URL and the admin's credentials.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const CHECKOUTS = Number(__ENV.CHECKOUTS || 60);
// Seconds between one user's checkouts: long enough that the run outlasts the failures.
const PACE = Number(__ENV.PACE || 1);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const retried = new Counter('checkout_retries');

export const options = {
  scenarios: {
    checkouts: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 2),
      iterations: CHECKOUTS,
      maxDuration: '5m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
  },
};

function login(email, password) {
  const r = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), {
    headers: JSON_HEADERS,
  });
  if (r.status !== 200) fail(`login for ${email} answered ${r.status}`);
  return r.json('accessToken');
}

function auth(token, extra = {}) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}`, ...extra } };
}

export function setup() {
  const admin = login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);

  const sku = `CHAOS-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  const created = http.post(
    `${BASE_URL}/api/v1/admin/products`,
    JSON.stringify({ sku, name: `chaos ${sku}`, category: 'chaos', priceMinor: 1000, currency: 'USD' }),
    auth(admin),
  );
  if (created.status !== 201) fail(`product creation answered ${created.status}`);
  const product = created.json('id');

  // Inventory learns of the product from ProductCreated a moment later; restock once it has.
  const restock = JSON.stringify({ delta: CHECKOUTS * 2, reason: 'RESTOCK' });
  let stocked = false;
  for (let i = 0; i < 60 && !stocked; i++) {
    stocked =
      http.post(`${BASE_URL}/api/v1/admin/inventory/${product}/adjustments`, restock, auth(admin))
        .status === 200;
    if (!stocked) sleep(1);
  }
  if (!stocked) fail('inventory never learned of the product');

  const email = `chaos-${crypto.randomUUID()}@example.com`;
  const password = `chaos-${crypto.randomUUID()}`;
  const registered = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ email, password, displayName: 'Chaos Test' }),
    { headers: JSON_HEADERS },
  );
  if (registered.status !== 201) fail(`register answered ${registered.status}`);
  return { product, customer: login(email, password) };
}

export default function (data) {
  const key = `chaos-${crypto.randomUUID()}`;
  const body = JSON.stringify({
    items: [{ productId: data.product, quantity: 1 }],
    paymentToken: 'tok_success',
  });
  let r;
  for (let attempt = 0; attempt < 8; attempt++) {
    // A hung pod accepts the connection and never answers: give up after 5 s and retry, as a
    // browser user pressing the button again would, with the same key.
    const params = { ...auth(data.customer, { 'Idempotency-Key': key }), timeout: '5s' };
    r = http.post(`${BASE_URL}/api/v1/orders`, body, params);
    // 202 accepts a new order; 200 is a retry of one already accepted (the same key returns the
    // original order). Anything else that is not the client's fault is worth the same retry.
    if (r.status === 202 || r.status === 200) break;
    if (r.status >= 400 && r.status < 500 && r.status !== 429) break;
    retried.add(1);
    sleep(Math.min(0.5 * 2 ** attempt, 5));
  }
  check(r, { 'checkout accepted': (res) => res.status === 202 || res.status === 200 });
  sleep(PACE);
}

export function teardown(data) {
  // Wait for every saga to finish, then compare what the customer has with what was sent.
  let orders = [];
  for (let i = 0; i < 90; i++) {
    const r = http.get(`${BASE_URL}/api/v1/orders?size=100`, auth(data.customer));
    if (r.status === 200) {
      orders = r.json('items');
      if (orders.length >= CHECKOUTS && orders.every((o) => o.status === 'CONFIRMED' || o.status === 'FAILED')) {
        break;
      }
    }
    sleep(2);
  }
  const confirmed = orders.filter((o) => o.status === 'CONFIRMED').length;
  console.log(`orders: ${orders.length} for ${CHECKOUTS} checkouts, ${confirmed} confirmed`);
  check(orders, {
    'exactly one order per checkout (no duplicates from retries)': (o) => o.length === CHECKOUTS,
    'every order confirmed': (o) => o.length > 0 && o.every((x) => x.status === 'CONFIRMED'),
  });
}
