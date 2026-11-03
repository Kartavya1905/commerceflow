import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end smoke test against the running Compose stack (scripts/dev-up.sh --apps), through
 * the web app on http://localhost:8000. localhost matters: it is what lets the browser keep the
 * Secure refresh-token cookie over plain HTTP. scripts/e2e.sh runs this in the Playwright image
 * on the host network.
 */
export default defineConfig({
  testDir: "e2e",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8000",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
