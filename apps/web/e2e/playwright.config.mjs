import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: "*.spec.mjs",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [["line"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true",
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
