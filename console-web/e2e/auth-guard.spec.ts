/**
 * Gate: authentication and role routing (spec §6 "auth-guard/refresh gate").
 * The guards are cosmetic — this proves the console sends people to the right
 * place and to the BFF login, not that it enforces anything.
 */
import { expect, test, type BrowserContext } from "@playwright/test";

async function signInAs(context: BrowserContext, baseURL: string, persona: "platform" | "institution") {
  await context.addCookies([
    { name: "stub_session", value: persona, url: baseURL },
    { name: "XSRF-TOKEN", value: "stub-xsrf", url: baseURL },
  ]);
}

test("unauthenticated → redirected to /bff/login with returnTo", async ({ page }) => {
  await page.goto("/clients?x=1");
  await expect(page).toHaveURL(/\/bff\/login\?returnTo=%2Fclients%3Fx%3D1$/);
  await expect(page.getByTestId("return-to")).toHaveText("/clients?x=1");
});

test("unauthenticated at / → /bff/login", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/bff\/login\?returnTo=%2F$/);
});

test("authenticated platform admin lands on /dashboard", async ({ page, context, baseURL }) => {
  await signInAs(context, baseURL!, "platform");
  await page.goto("/");
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("heading", { level: 1, name: "Overview" })).toBeVisible();
  await expect(page.getByTestId("current-user")).toHaveText("admin@risa.gov.rw");
  // Sections rendered from the stubbed /dashboard/overview.
  await expect(page.getByTestId("section-logins")).toBeVisible();
});

test("authenticated institution admin lands on /institution and cannot open the platform area", async ({ page, context, baseURL }) => {
  await signInAs(context, baseURL!, "institution");
  await page.goto("/");
  await expect(page).toHaveURL(/\/institution$/);
  await expect(page.locator("main[data-area=institution]")).toBeVisible();
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/institution$/);
});

test("a 401 mid-session after the BFF refresh fails → re-login → back where they were", async ({ page, context, baseURL }) => {
  await signInAs(context, baseURL!, "platform");
  await page.goto("/dashboard");
  await expect(page.getByTestId("section-logins")).toBeVisible();

  // The BFF can no longer refresh: every API call now answers 401 and the
  // BFF session reads as signed out.
  await context.addCookies([{ name: "stub_expired", value: "1", url: baseURL! }]);

  // The next API call (refreshing the overview) answers 401 → the SPA
  // re-checks /bff/session → signed out → off to the BFF login.
  await page.getByTestId("overview-refresh").click();
  await expect(page).toHaveURL(/\/bff\/login\?returnTo=%2Fdashboard$/);

  // Re-login through the (stub) BFF returns to the page they were on.
  await page.getByTestId("login-platform").click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByTestId("section-logins")).toBeVisible();
  await expect(page.getByTestId("current-user")).toHaveText("admin@risa.gov.rw");
});

test("signed in with no admin role → /no-access", async ({ page, context, baseURL }) => {
  // A persona the stub does not know reads as signed out, so use a route mock
  // for this one case.
  await page.route("**/bff/session", (route) =>
    route.fulfill({ json: { authenticated: true, username: "citizen", roles: [], platformAdmin: false, institutionAdmin: false } }),
  );
  await signInAs(context, baseURL!, "platform");
  await page.goto("/");
  await expect(page).toHaveURL(/\/no-access$/);
  await expect(page.getByRole("heading", { name: "No administrative access" })).toBeVisible();
});
