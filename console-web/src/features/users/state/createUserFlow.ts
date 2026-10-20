/**
 * The create-user flow, step by step (pure orchestration over injected calls,
 * so it is testable without React):
 *
 *   1. POST /users/                       — the account (emailVerified=true)
 *   2. PUT  /users/{id}/reset-password    — the initial password
 *   3. POST /agency/{groupId}/members     — mandatory institution membership
 *   4. per chosen client: read the user's client-access cell, then
 *      POST /authorization/users/{id}/client-access/{clientId} {grant, expected_state}
 *
 * Steps 2–4 run only once the user exists; a failure there does not undo the
 * account — the caller lands on the user's page with a warning, as in the
 * reference.
 */
import type { CreateUserBody } from "../api";
import type { UserClientAccess, UserClientAccessState } from "../types";
import type { CreateUserValues } from "./userForms";

export interface CreateUserDeps {
  createUser: (body: CreateUserBody) => Promise<{ id: string }>;
  resetPassword: (id: string, value: string, temporary: boolean) => Promise<unknown>;
  addAgencyMember: (groupId: string, userId: string) => Promise<unknown>;
  readAccess: (id: string) => Promise<UserClientAccess>;
  changeAccess: (id: string, clientId: string, action: "grant", expected: UserClientAccessState) => Promise<unknown>;
}

export type CreateUserOutcome =
  | { ok: true; userId: string }
  | { ok: false; userId: string; step: "password" | "agency" | "clients"; failedClients?: string[]; message: string };

export function createUserBody(v: CreateUserValues): CreateUserBody {
  return {
    username: v.username.trim(),
    email: v.email.trim(),
    firstName: v.firstName.trim(),
    lastName: v.lastName.trim(),
    enabled: true,
    // Set so users do not hit the verification flow on first login.
    emailVerified: true,
    ...(v.employeeNumber.trim() ? { attributes: { employee_number: [v.employeeNumber.trim()] } } : {}),
  };
}

/** Throws only when the account itself cannot be created. */
export async function runCreateUser(v: CreateUserValues, d: CreateUserDeps): Promise<CreateUserOutcome> {
  const { id } = await d.createUser(createUserBody(v));
  const msg = (e: unknown) => (e instanceof Error ? e.message : String(e));
  try {
    await d.resetPassword(id, v.password, v.temporary);
  } catch (e) {
    return { ok: false, userId: id, step: "password", message: msg(e) };
  }
  try {
    await d.addAgencyMember(v.agencyId, id);
  } catch (e) {
    return { ok: false, userId: id, step: "agency", message: msg(e) };
  }
  if (v.clientIds.length) {
    const failed: string[] = [];
    let states: Map<string, UserClientAccessState> = new Map();
    try {
      states = new Map((await d.readAccess(id)).items.map((i) => [i.client_id, i.state]));
    } catch {
      /* fall back to not_granted per client */
    }
    for (const clientId of v.clientIds) {
      try {
        await d.changeAccess(id, clientId, "grant", states.get(clientId) ?? "not_granted");
      } catch {
        failed.push(clientId);
      }
    }
    if (failed.length) return { ok: false, userId: id, step: "clients", failedClients: failed, message: failed.join(", ") };
  }
  return { ok: true, userId: id };
}
