import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, server } from "@/test/server";
import { renderInstitutionPage } from "@/test/institution";
import { Component as RevokePage } from "../../pages/AccessRevokePage";
import type { RevokeRequestBody } from "../types";

describe("revoke access — user-first", () => {
  it("preview comes from the server; confirm sends the real call without preview", async () => {
    const revokeBodies: RevokeRequestBody[] = [];
    server.use(
      http.get(api("/institution-admin/users"), () =>
        HttpResponse.json([
          { id: "u1", username: "alice", email: "a@x.rw", enabled: true },
          { id: "u2", username: "bob", email: "b@x.rw", enabled: true },
        ]),
      ),
      http.post(api("/institution-admin/access/clients-for-users"), () =>
        HttpResponse.json({
          institution: "minagri",
          clients: [{ client_id: "minagri-portal", name: "Portal", cross_cutting: false, revocable: true, user_ids: ["u1"], holders: 1 }],
          by_user: { u1: ["minagri-portal"] },
          usernames: { u1: "alice" },
          total_pairs: 1,
        }),
      ),
      http.post(api("/institution-admin/access/revoke"), async ({ request }) => {
        const body = (await request.json()) as RevokeRequestBody;
        revokeBodies.push(body);
        return HttpResponse.json({
          institution: "minagri",
          preview: body.preview,
          all_clients: false,
          clients_considered: ["minagri-portal"],
          users: [{ user_id: "u1", username: "alice", revoked: ["minagri-portal"], skipped: [] }],
          total_revocations: 1,
          total_skipped: 0,
          users_affected: 1,
        });
      }),
    );
    renderInstitutionPage(<RevokePage />, { route: "/institution/access/revoke", path: "/institution/access/revoke" });
    await userEvent.click(await screen.findByRole("checkbox", { name: "alice" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(await screen.findByRole("checkbox", { name: "minagri-portal" }));
    await userEvent.click(screen.getByRole("button", { name: "Review removal" }));

    expect(await screen.findByTestId("revoke-summary")).toHaveTextContent("1 revocation");
    expect(revokeBodies[0]).toMatchObject({ user_ids: ["u1"], client_ids: ["minagri-portal"], preview: true, all_clients: false, confirm_all_clients: false });

    await userEvent.click(screen.getByRole("button", { name: "Remove access" }));
    await waitFor(() => expect(revokeBodies).toHaveLength(2));
    expect(revokeBodies[1]).toMatchObject({ user_ids: ["u1"], client_ids: ["minagri-portal"], preview: false });
  });
});
