import { describe, expect, it, vi } from "vitest";

import { runCreateUser, type CreateUserDeps } from "./createUserFlow";
import type { CreateUserValues } from "./userForms";

const values: CreateUserValues = {
  username: "jdoe",
  email: "j@risa.gov.rw",
  firstName: "J",
  lastName: "Doe",
  employeeNumber: " EMP1 ",
  password: "Abcdefghi1",
  temporary: true,
  agencyId: "g1",
  clientIds: ["minagri-a", "minagri-b"],
};

function deps(over: Partial<CreateUserDeps> = {}): CreateUserDeps {
  return {
    createUser: vi.fn(async () => ({ id: "u1" })),
    resetPassword: vi.fn(async () => undefined),
    addAgencyMember: vi.fn(async () => undefined),
    readAccess: vi.fn(async () => ({
      user_id: "u1",
      username: "jdoe",
      groups: [],
      items: [{ client_id: "minagri-a", state: "granted" }, { client_id: "minagri-b", state: "not_granted" }] as never,
    })),
    changeAccess: vi.fn(async () => undefined),
    ...over,
  };
}

describe("runCreateUser", () => {
  it("creates, sets the password, joins the institution, grants with the observed state", async () => {
    const d = deps();
    expect(await runCreateUser(values, d)).toEqual({ ok: true, userId: "u1" });
    expect(d.createUser).toHaveBeenCalledWith(expect.objectContaining({ username: "jdoe", emailVerified: true, enabled: true, attributes: { employee_number: ["EMP1"] } }));
    expect(d.resetPassword).toHaveBeenCalledWith("u1", "Abcdefghi1", true);
    expect(d.addAgencyMember).toHaveBeenCalledWith("g1", "u1");
    expect(d.changeAccess).toHaveBeenCalledWith("u1", "minagri-a", "grant", "granted");
    expect(d.changeAccess).toHaveBeenCalledWith("u1", "minagri-b", "grant", "not_granted");
  });

  it("stops after a password failure, reporting the user", async () => {
    const d = deps({ resetPassword: vi.fn(async () => Promise.reject(new Error("policy"))) });
    expect(await runCreateUser(values, d)).toEqual({ ok: false, userId: "u1", step: "password", message: "policy" });
    expect(d.addAgencyMember).not.toHaveBeenCalled();
  });

  it("names the clients that failed", async () => {
    const d = deps({ changeAccess: vi.fn(async (_u: string, c: string) => (c === "minagri-b" ? Promise.reject(new Error("x")) : undefined)) });
    expect(await runCreateUser(values, d)).toMatchObject({ ok: false, step: "clients", failedClients: ["minagri-b"] });
  });

  it("propagates a failure to create the account", async () => {
    await expect(runCreateUser(values, deps({ createUser: vi.fn(async () => Promise.reject(new Error("exists"))) }))).rejects.toThrow("exists");
  });
});
