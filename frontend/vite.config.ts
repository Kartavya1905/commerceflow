/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In development the app is served by Vite and /api is proxied to the gateway, so the browser
// sees one origin, exactly as in Compose (where nginx does the proxying). Same origin is what lets
// the refresh token live in a SameSite=Strict cookie with no CORS at all.
const gateway = process.env.GATEWAY_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/api": { target: gateway, changeOrigin: false } },
  },
  build: { sourcemap: false },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
