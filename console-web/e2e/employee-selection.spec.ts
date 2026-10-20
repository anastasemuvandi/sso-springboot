/**
 * Gate: employee selection for provisioning (spec §6 "employee-selection gate").
 *
 * As an institution admin: select employees on page 1, move to page 2 and
 * select more (the selection survives paging), continue to the review step,
 * pick an application, run the DRY RUN (a report, nothing written), then
 * confirm (the final report). The stub wrapper echoes the selection back as
 * the provisioning report, so what reaches it is what the admin selected.
 */
import { expect, test } from "@playwright/test";

test.beforeEach(async ({ context, baseURL }) => {
  await context.addCookies([
    { name: "stub_session", value: "institution", url: baseURL! },
    { name: "XSRF-TOKEN", value: "stub-xsrf", url: baseURL! },
  ]);
});

test("select across two pages → dry run report → confirm", async ({ page }) => {
  const provisionBodies: Array<{ employee_numbers: string[]; client_ids: string[]; dry_run: boolean }> = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/institution-admin/employees/provision") && r.method() === "POST") {
      provisionBodies.push(r.postDataJSON());
    }
  });

  await page.goto("/institution/employees");
  await expect(page.getByTestId("employee-row-E0001")).toBeVisible();
  await page.getByTestId("start-selection").click();
  await expect(page.getByTestId("selection-count")).toHaveText(/^0 selected/);

  // Page 1: two employees; a leaver (E0007) and a no-account-no-email row (E0020) cannot be ticked.
  await page.getByTestId("select-E0002").click();
  await page.getByTestId("select-E0003").click();
  await expect(page.getByTestId("select-E0007")).toBeDisabled();
  await expect(page.getByTestId("select-E0020")).toBeDisabled();
  await expect(page.getByTestId("selection-count")).toHaveText(/^2 selected/);

  // Page 2: the selection survives paging.
  await page.getByTestId("page-next").click();
  await expect(page.getByTestId("page-indicator")).toHaveText("Page 2 of 2");
  await expect(page.getByTestId("employee-row-E0051")).toBeVisible();
  await expect(page.getByTestId("selection-count")).toHaveText(/^2 selected/);
  await page.getByTestId("select-E0053").click();
  await expect(page.getByTestId("selection-count")).toHaveText(/^3 selected/);

  // Back to page 1: the ticks are still there.
  await page.getByTestId("page-prev").click();
  await expect(page.getByTestId("select-E0002")).toBeChecked();

  await page.getByTestId("continue-provision").click();
  await expect(page).toHaveURL(/\/institution\/employees\/provision$/);
  await expect(page.getByTestId("provision-selected-count")).toHaveText("3 employees selected");
  for (const n of ["E0002", "E0003", "E0053"]) await expect(page.getByTestId(`selected-${n}`)).toBeVisible();

  // Confirm needs a dry run first.
  await expect(page.getByTestId("confirm-provision")).toBeDisabled();
  await page.getByTestId("app-minagri-kubaka").click();
  await page.getByTestId("dry-run").click();

  const preview = page.getByTestId("provision-report-preview");
  await expect(preview).toBeVisible();
  await expect(preview.getByText("Dry run — nothing was changed")).toBeVisible();
  await expect(preview.getByTestId("report-row-E0053").getByTestId("report-account")).toHaveText("created");
  await expect(preview.getByTestId("report-row-E0053").getByTestId("report-granted")).toHaveText("minagri-kubaka");

  await page.getByTestId("confirm-provision").click();
  const final = page.getByTestId("provision-report-final");
  await expect(final).toBeVisible();
  await expect(final.getByText("Access assigned")).toBeVisible();

  expect(provisionBodies).toEqual([
    { employee_numbers: ["E0002", "E0003", "E0053"], client_ids: ["minagri-kubaka"], dry_run: true },
    { employee_numbers: ["E0002", "E0003", "E0053"], client_ids: ["minagri-kubaka"], dry_run: false },
  ]);

  // The selection is cleared after a successful run.
  await page.goto("/institution/employees?select=1");
  await expect(page.getByTestId("selection-count")).toHaveText(/^0 selected/);
});

test("select-all on a page skips rows that cannot be provisioned", async ({ page }) => {
  await page.goto("/institution/employees?select=1");
  await expect(page.getByTestId("employee-row-E0001")).toBeVisible();
  await page.getByTestId("select-page").click();
  // 50 rows on page 1: E0007 is a leaver; E0020/E0030/E0050 have no email and
  // no account (E0040 has an account) → 46 selectable.
  await expect(page.getByTestId("selection-count")).toHaveText(/^46 selected/);
  await page.getByTestId("select-page").click();
  await expect(page.getByTestId("selection-count")).toHaveText(/^0 selected/);
});
