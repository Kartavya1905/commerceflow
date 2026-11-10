// Milestone 12: the data set the performance workloads run against (scripts/perf.sh seeds it once).
//
// Through the public API, as an admin and as customers would:
// - PRODUCTS products (default 1,000) in 8 categories, named from word lists so that searches match
//   different numbers of them;
// - stock for the first 20, which the orders use;
// - CUSTOMERS customers (default 100), each placing ORDERS orders (default 5) through the full
//   checkout saga, all CONFIRMED before it finishes.
//
// The customers' generated sign-ins and their order ids go to /out/perf-users.json, which
// scripts/perf.sh keeps under the git-ignored secrets/ folder.
import http from 'k6/http';
import { fail, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://api-gateway:8080';
const PRODUCTS = Number(__ENV.PRODUCTS || 1000);
const CUSTOMERS = Number(__ENV.CUSTOMERS || 100);
const ORDERS = Number(__ENV.ORDERS || 5);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const CATEGORIES = ['lighting', 'furniture', 'kitchen', 'office', 'garden', 'bath', 'decor', 'storage'];
const ADJECTIVES = ['oak', 'steel', 'linen', 'ceramic', 'walnut', 'brass', 'glass', 'cotton', 'stone', 'bamboo'];
const NOUNS = ['lamp', 'desk', 'mug', 'chair', 'shelf', 'bowl', 'stool', 'vase', 'tray', 'basket', 'clock', 'rug'];

export const options = {
  scenarios: { seed: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '30m' } },
  setupTimeout: '30m',
};

function auth(token, extra = {}) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}`, ...extra } };
}

function login(email, password) {
  const r = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS });
  if (r.status !== 200) fail(`login answered ${r.status}`);
  return r.json('accessToken');
}

export function setup() {
  const admin = login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
  const run = crypto.randomUUID().slice(0, 6).toUpperCase();

  const products = [];
  for (let i = 0; i < PRODUCTS; i++) {
    const name = `${ADJECTIVES[i % ADJECTIVES.length]} ${NOUNS[Math.floor(i / ADJECTIVES.length) % NOUNS.length]} ${i}`;
    const body = {
      sku: `PERF-${run}-${String(i).padStart(5, '0')}`,
      name,
      category: CATEGORIES[i % CATEGORIES.length],
      priceMinor: 500 + ((i * 37) % 20000),
      currency: 'USD',
    };
    const r = http.post(`${BASE_URL}/api/v1/admin/products`, JSON.stringify(body), auth(admin));
    if (r.status !== 201) fail(`product ${i} answered ${r.status}`);
    products.push(r.json('id'));
  }

  // Stock for the products the orders use, once inventory has heard of each.
  const stocked = products.slice(0, 20);
  for (const id of stocked) {
    let ok = false;
    for (let attempt = 0; attempt < 60 && !ok; attempt++) {
      ok = http.post(`${BASE_URL}/api/v1/admin/inventory/${id}/adjustments`,
        JSON.stringify({ delta: 100000, reason: 'RESTOCK' }), auth(admin)).status === 200;
      if (!ok) sleep(1);
    }
    if (!ok) fail(`inventory never learned of ${id}`);
  }

  const users = [];
  for (let c = 0; c < CUSTOMERS; c++) {
    const email = `perf-${run.toLowerCase()}-${c}@example.com`;
    const password = `perf-${crypto.randomUUID()}`;
    const reg = http.post(`${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({ email, password, displayName: `Perf ${c}` }), { headers: JSON_HEADERS });
    if (reg.status !== 201) fail(`register ${c} answered ${reg.status}`);
    const token = login(email, password);
    const orders = [];
    for (let o = 0; o < ORDERS; o++) {
      const body = {
        items: [{ productId: stocked[(c + o) % stocked.length], quantity: 1 }],
        paymentToken: 'tok_success',
      };
      const r = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify(body),
        // 16 to 64 letters, digits and dashes (order-service's contract).
        auth(token, { 'Idempotency-Key': `perf-${crypto.randomUUID()}` }));
      if (r.status !== 202) fail(`order ${c}/${o} answered ${r.status}`);
      orders.push(r.json('id'));
    }
    users.push({ email, password, orders });
  }

  // Every saga finished, so the read workload sees settled orders.
  for (const u of users) {
    const token = login(u.email, u.password);
    for (const id of u.orders) {
      let status = '';
      for (let attempt = 0; attempt < 60 && status !== 'CONFIRMED'; attempt++) {
        status = http.get(`${BASE_URL}/api/v1/orders/${id}`, auth(token)).json('status');
        if (status !== 'CONFIRMED') sleep(1);
      }
      if (status !== 'CONFIRMED') fail(`order ${id} ended ${status}`);
    }
  }
  return { run, products: products.length, users };
}

export default function () {}

export function handleSummary(data) {
  const seeded = data.setup_data;
  if (!seeded) {
    return { stdout: 'seeding failed; see the error above\n' };
  }
  return {
    '/out/perf-users.json': JSON.stringify(seeded, null, 1),
    stdout: `seeded run ${seeded.run}: ${seeded.products} products, ${seeded.users.length} customers, ` +
      `${seeded.users.reduce((n, u) => n + u.orders.length, 0)} confirmed orders\n`,
  };
}
