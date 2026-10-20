import { defineConfig, devices } from "@playwright/test";

/**
 * The gate runs the BUILT console (`vite preview` of dist/, with the same
 * /api + /bff proxy as dev) against `e2e/stub-server.mjs`, which stands in
 * for the BFF and the wrapper. Run `npm run build` first.
 */
const STUB_PORT = Number(process.env.STUB_PORT ?? 3999);
const PREVIEW_PORT = Number(process.env.PREVIEW_PORT ?? 4173);

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"]],
  // Cold `vite preview` + lazy chunks under parallel workers can exceed the 5 s default.
  expect: { timeout: 15_000 },
  use: {
    baseURL: `http://localhost:${PREVIEW_PORT}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `node e2e/stub-server.mjs`,
      env: { STUB_PORT: String(STUB_PORT) },
      url: `http://127.0.0.1:${STUB_PORT}/bff/session`,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `npx vite preview --port ${PREVIEW_PORT} --strictPort`,
      env: { CONSOLE_BFF_URL: `http://127.0.0.1:${STUB_PORT}` },
      url: `http://localhost:${PREVIEW_PORT}/`,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
