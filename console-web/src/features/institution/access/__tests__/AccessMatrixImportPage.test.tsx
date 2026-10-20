import { describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { toApiError } from "@/api/errors";
import { api, server } from "@/test/server";
import { renderInstitutionPage } from "@/test/institution";
import { Component as ImportPage } from "../../pages/AccessMatrixImportPage";
import type { ImportPreview } from "../types";
import { importFormData, importPath } from "../api";

/*
 * jsdom's File/FormData cannot be streamed by Node's fetch (the multipart
 * request never completes in this runtime), so the upload call is replaced
 * here and the request is checked separately (`importFormData`, `importPath`).
 * The confirm step goes through MSW as usual.
 */
const previewCalls = vi.hoisted(() => [] as Array<{ scope: unknown; name: string; approvedBy: string }>);
const previewResult = vi.hoisted(() => ({ value: null as unknown, error: null as unknown }));
vi.mock("../api", async (importOriginal) => {
  const orig = await importOriginal<typeof import("../api")>();
  return {
    ...orig,
    previewImport: async (scope: unknown, file: File, approvedBy: string) => {
      previewCalls.push({ scope, name: file.name, approvedBy });
      if (previewResult.error) throw previewResult.error;
      return previewResult.value;
    },
  };
});

const preview: ImportPreview = {
  import_id: "imp-1234abcd",
  expires_at: "2026-10-20T11:00:00Z",
  institution: "minagri",
  approved_by: "PS MINAGRI",
  file_name: "matrix.xlsx",
  file_sha256: "abc",
  export_id: "exp-1",
  exported_at: "2026-10-20T09:00:00Z",
  exported_by: "hr@minagri.gov.rw",
  clients: ["minagri-portal"],
  summary: { will_grant: 1, will_create_account: 1, removal_ignored: 1, rejected: 0, invalid: 0, already_has_access: 0 },
  items: [
    { row: 2, employee_number: "E1", name: "Alice", client_id: "minagri-portal", outcome: "will_grant", reason: null, message: null },
    { row: 3, employee_number: "E2", name: "Bob", client_id: "minagri-portal", outcome: "will_create_account", reason: null, message: null },
    { row: 4, employee_number: "E3", name: "Carol", client_id: "minagri-portal", outcome: "removal_ignored", reason: "cleared", message: "YES was cleared; imports never revoke." },
  ],
};

describe("access matrix import", () => {
  it("multipart carries only the file; approved_by and the scope go in the query (wrapper contract)", () => {
    const form = importFormData(new File(["x"], "m.xlsx"));
    expect((form.get("file") as File).name).toBe("m.xlsx");
    expect([...form.keys()]).toEqual(["file"]);
    expect(importPath({ institution: "minagri" }, "PS MINAGRI")).toBe("/institution-admin/access-matrix/import?institution=minagri&approved_by=PS+MINAGRI");
    expect(importPath({}, "PS")).toBe("/institution-admin/access-matrix/import?approved_by=PS");
  });

  it("upload → preview (cleared YES shown as not revoked) → confirm applies the stored plan → results", async () => {
    previewCalls.length = 0;
    previewResult.value = preview;
    previewResult.error = null;
    const applied: string[] = [];
    server.use(
      http.post(api("/institution-admin/access-matrix/import/:id/apply"), ({ params }) => {
        applied.push(String(params.id));
        return HttpResponse.json({
          institution: "minagri",
          action: "grant",
          channel: "bulk",
          batch_id: "b2",
          import_id: "imp-1234abcd",
          approved_by: "PS MINAGRI",
          file_name: "matrix.xlsx",
          summary: { applied: 2, rejected: 0, failed: 0 },
          results: [
            { employee_number: "E1", client_id: "minagri-portal", status: "applied" },
            { employee_number: "E2", client_id: "minagri-portal", status: "applied", account_created: true, setup_email: "sent" },
          ],
        });
      }),
    );
    renderInstitutionPage(<ImportPage />, { route: "/institution/access/matrix/import?client=minagri-portal", path: "/institution/access/matrix/import" });

    const fileInput = (await screen.findByLabelText("Edited export (.xlsx)")) as HTMLInputElement;
    await userEvent.upload(fileInput, new File(["xlsx-bytes"], "matrix.xlsx", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }));
    await userEvent.type(screen.getByLabelText(/Approved by/), "PS MINAGRI");
    await userEvent.click(screen.getByRole("button", { name: "Preview import" }));

    const previewEl = await screen.findByTestId("import-preview");
    expect(previewCalls).toEqual([{ scope: {}, name: "matrix.xlsx", approvedBy: "PS MINAGRI" }]);
    // The attention list shows the cleared YES as "not revoked".
    const table = within(previewEl).getByRole("table");
    expect(within(table).getByText("Cleared, not revoked")).toBeInTheDocument();
    expect(within(table).getByText("YES was cleared; imports never revoke.")).toBeInTheDocument();
    expect(previewEl).toHaveTextContent("Nothing is revoked.");

    await userEvent.click(within(previewEl).getByRole("button", { name: "Confirm 2 grant(s)" }));
    const done = await screen.findByTestId("import-done");
    expect(applied).toEqual(["imp-1234abcd"]);
    expect(done).toHaveTextContent("2 granted");
    expect(within(done).getByText("Account created; set-password email sent.")).toBeInTheDocument();
    expect(within(done).getAllByText("applied")).toHaveLength(2);
  });

  it("a refused upload stays on the upload step with the wrapper's message", async () => {
    previewResult.error = toApiError(400, { error: "bad_signature", message: "This file was not exported by this console (signature mismatch)." });
    renderInstitutionPage(<ImportPage />, { route: "/institution/access/matrix/import", path: "/institution/access/matrix/import" });
    await userEvent.upload((await screen.findByLabelText("Edited export (.xlsx)")) as HTMLInputElement, new File(["x"], "m.xlsx"));
    await userEvent.type(screen.getByLabelText(/Approved by/), "PS");
    await userEvent.click(screen.getByRole("button", { name: "Preview import" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("signature mismatch");
  });
});
