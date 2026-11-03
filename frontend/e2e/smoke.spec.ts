import { expect, test } from "@playwright/test";
import type { APIRequestContext } from "@playwright/test";

/**
 * ROADMAP M8 acceptance: register, buy, see CONFIRMED; admin pages hidden from customers AND
 * refused by the API. Runs against the real stack: real gateway, saga, sandbox charge.
 */

const adminEmail = process.env.E2E_ADMIN_EMAIL ?? "";
const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? "";
const unique = `${Date.now()}`;
// Registered through the UI by the first test.
const newcomer = {
  name: "Smoke Tester",
  email: `e2e-${unique}@example.com`,
  password: `e2e-pass-${unique}-x`,
};
// Registered through the API in setup, so the other tests never depend on the first one passing.
const customer = {
  name: "Returning Tester",
  email: `e2e-${unique}-r@example.com`,
  password: `e2e-pass-${unique}-r`,
};
let productId = "";
const productName = `E2E lamp ${unique}`;

async function tokenFor(request: APIRequestContext, email: string, password: string) {
  const response = await request.post("/api/v1/auth/login", {
    data: { email, password },
    headers: { "X-Token-Delivery": "body" },
  });
  expect(response.status(), `login as ${email}`).toBe(200);
  return ((await response.json()) as { accessToken: string }).accessToken;
}

test.beforeAll(async ({ request }) => {
  const registered = await request.post("/api/v1/auth/register", {
    data: { email: customer.email, password: customer.password, displayName: customer.name },
  });
  expect(registered.status(), "register the returning customer").toBe(201);
  expect(adminEmail, "E2E_ADMIN_EMAIL (scripts/e2e.sh reads it from .env)").not.toBe("");
  const admin = await tokenFor(request, adminEmail, adminPassword);
  const headers = { Authorization: `Bearer ${admin}` };
  const created = await request.post("/api/v1/admin/products", {
    headers,
    data: {
      sku: `E2E-${unique}`,
      name: productName,
      category: "e2e",
      priceMinor: 2500,
      currency: "USD",
    },
  });
  expect(created.status()).toBe(201);
  productId = ((await created.json()) as { id: string }).id;
  // Inventory hears of the product from an event, a moment later.
  await expect
    .poll(
      async () =>
        (
          await request.post(`/api/v1/admin/inventory/${productId}/adjustments`, {
            headers,
            data: { delta: 5, reason: "RESTOCK" },
          })
        ).status(),
      { timeout: 60_000 },
    )
    .toBe(200);
});

test("a new customer registers, buys, and sees the order confirmed", async ({ page }) => {
  await page.goto("/register");
  await page.getByLabel("Name").fill(newcomer.name);
  await page.getByLabel("Email").fill(newcomer.email);
  await page.getByLabel("Password").fill(newcomer.password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByText(newcomer.name)).toBeVisible();

  await page.goto(`/products/${productId}`);
  await expect(page.getByRole("heading", { name: productName })).toBeVisible();
  await expect(page.getByText("5 in stock")).toBeVisible();
  await page.getByRole("button", { name: "Add to cart" }).click();
  await expect(page.getByRole("status")).toHaveText("Added to your cart.");

  await page.getByRole("link", { name: "Cart" }).click();
  await expect(page.getByRole("link", { name: productName })).toBeVisible();
  await page.getByRole("link", { name: "Check out" }).click();
  await page.getByLabel("Card that is charged").check();
  await page.getByRole("button", { name: "Place order" }).click();

  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  // The saga reserves, charges the sandbox and commits; the page follows it live.
  await expect(page.getByTestId("order-status")).toHaveText("Confirmed", { timeout: 90_000 });

  await page.getByRole("link", { name: "Orders" }).click();
  await expect(page.getByRole("cell", { name: "Confirmed" }).first()).toBeVisible();
});

test("a reload keeps the session: the refresh cookie brings it back", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(customer.email);
  await page.getByLabel("Password").fill(customer.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(customer.name)).toBeVisible();
  await page.reload();
  await expect(page.getByText(customer.name)).toBeVisible();
  // The cookie is invisible to page scripts, and nothing is kept in web storage.
  expect(await page.evaluate(() => document.cookie)).not.toContain("cf_refresh");
  expect(await page.evaluate(() => window.localStorage.length + window.sessionStorage.length)).toBe(
    0,
  );
});

test("admin pages are hidden from a customer, and the API refuses them", async ({ page, request }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(customer.email);
  await page.getByLabel("Password").fill(customer.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(customer.name)).toBeVisible();
  await expect(page.getByRole("link", { name: "Admin" })).toHaveCount(0);
  await page.goto("/admin/dead-letters");
  await expect(page.getByRole("alert")).toHaveText(/for administrators/);

  // Hiding is not the protection: the API itself says 403 to a customer's token.
  const token = await tokenFor(request, customer.email, customer.password);
  for (const path of [
    "/api/v1/admin/orders",
    "/api/v1/admin/sagas",
    "/api/v1/admin/payments",
    "/api/v1/admin/dlt/order.events.v1.dlt",
  ]) {
    const response = await request.get(path, { headers: { Authorization: `Bearer ${token}` } });
    expect(response.status(), path).toBe(403);
  }
});

test("an admin sees the admin pages", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(adminEmail);
  await page.getByLabel("Password").fill(adminPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("link", { name: "Admin" }).click();
  await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();
  await page.getByRole("link", { name: "Products and stock" }).click();
  await expect(page.getByRole("cell", { name: new RegExp(productName) })).toBeVisible();
  await page.getByRole("link", { name: "Dead letters" }).click();
  await expect(page.getByRole("heading", { name: "Dead letters" })).toBeVisible();
  await page.getByRole("link", { name: "Sagas" }).click();
  await expect(page.getByRole("heading", { name: "Sagas" })).toBeVisible();
});
