// Console smoke: a REAL browser login through console-bff -> wrapper /oauth2/authorize -> Keycloak
// (Keycloakify login page) -> BFF callback, against a running compose stack.
//
//   make smoke                                   # CONSOLE_URL defaults to http://localhost:3001
//   CONSOLE_URL=http://localhost:13001 node deploy/smoke/console-login.mjs
//
// Uses the Playwright that console-web already installs (npm ci in console-web, then
// `npx playwright install chromium` once). Checks:
//   superadmin      lands on /dashboard and GET /api/dashboard/overview answers 200
//   minagri.admin   lands on /institution, overview 200, /institution/access/matrix loads (200)
// Exit code 0 = every check passed. Screenshots go to deploy/smoke/out/ (git-ignored by intent).
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(path.join(here, "../../console-web/package.json"));
const { chromium } = require("playwright");

const CONSOLE = (process.env.CONSOLE_URL ?? "http://localhost:3001").replace(/\/+$/, "");
const PASSWORD = process.env.SMOKE_PASSWORD ?? "GorSsoDev#2026";
const OUT = path.join(here, "out");
fs.mkdirSync(OUT, { recursive: true });

let failures = 0;
const ok = (msg) => console.log(`  PASS ${msg}`);
const fail = (msg) => { failures++; console.log(`  FAIL ${msg}`); };

async function login(browser, username) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const apiStatus = new Map();
  page.on("response", (r) => {
    const u = new URL(r.url());
    if (u.pathname.startsWith("/api/")) apiStatus.set(u.pathname, r.status());
  });
  await page.goto(`${CONSOLE}/`, { waitUntil: "domcontentloaded" });
  // Unauthenticated: the SPA sends us to /bff/login -> wrapper -> Keycloak.
  await page.waitForSelector("#username", { timeout: 60_000 });
  await page.fill("#username", username);
  await page.fill("#password", PASSWORD);
  await Promise.all([
    page.waitForURL((u) => u.toString().startsWith(CONSOLE) && !u.pathname.startsWith("/login"), { timeout: 60_000 }),
    page.click("#kc-login, [type=submit]"),
  ]);
  return { context, page, apiStatus };
}

async function waitApi(page, apiStatus, pathname, timeout = 30_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    if (apiStatus.has(pathname)) return apiStatus.get(pathname);
    await page.waitForTimeout(250);
  }
  return undefined;
}

async function check(browser, username, landing, apis, extra) {
  console.log(`${username}`);
  let s;
  try {
    s = await login(browser, username);
  } catch (e) {
    fail(`login: ${e.message.split("\n")[0]}`);
    return;
  }
  const { context, page, apiStatus } = s;
  try {
    await page.waitForURL((u) => u.pathname.startsWith(landing), { timeout: 30_000 });
    ok(`landed on ${new URL(page.url()).pathname}`);
  } catch {
    fail(`expected landing ${landing}, got ${page.url()}`);
  }
  for (const api of apis) {
    const st = await waitApi(page, apiStatus, api);
    st === 200 ? ok(`${api} -> 200`) : fail(`${api} -> ${st ?? "no request"}`);
  }
  if (extra) await extra(page, apiStatus);
  await page.screenshot({ path: path.join(OUT, `${username}.png`), fullPage: true });
  await context.close();
}

const browser = await chromium.launch();
try {
  await check(browser, "superadmin", "/dashboard", ["/api/dashboard/overview"]);
  await check(browser, "minagri.admin", "/institution", ["/api/institution-admin/overview"], async (page, apiStatus) => {
    await page.goto(`${CONSOLE}/institution/access/matrix`, { waitUntil: "domcontentloaded" });
    // The page first asks which applications become columns: tick the first, open the matrix.
    const box = page.getByRole("checkbox").first();
    await box.waitFor({ timeout: 30_000 });
    await box.check();
    await page.getByRole("button", { name: /open the matrix/i }).click();
    const st = await waitApi(page, apiStatus, "/api/institution-admin/access-matrix");
    st === 200 ? ok("/api/institution-admin/access-matrix -> 200 (matrix page)") : fail(`access matrix -> ${st ?? "no request"}`);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: path.join(OUT, "minagri.admin-matrix.png"), fullPage: true });
  });
} finally {
  await browser.close();
}
console.log(failures ? `\n${failures} check(s) FAILED` : "\nall console smoke checks passed");
process.exit(failures ? 1 : 0);
