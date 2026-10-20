import { describe, expect, it } from "vitest";

import { testQueryClient } from "@/test/render";
import type { Session } from "@/auth/session";
import { guards } from "./guards";

const req = (path: string) => new Request(`http://localhost${path}`);
const args = (path: string) => ({ request: req(path), params: {}, context: undefined });

async function outcome(p: Promise<unknown>): Promise<{ status: number; location: string | null; document: boolean } | null> {
  try {
    const r = await p;
    return r instanceof Response ? { status: r.status, location: r.headers.get("Location"), document: r.headers.has("X-Remix-Reload-Document") } : null;
  } catch (e) {
    if (e instanceof Response) return { status: e.status, location: e.headers.get("Location"), document: e.headers.has("X-Remix-Reload-Document") };
    throw e;
  }
}

const S = (s: Partial<Session>): Session => ({ authenticated: true, roles: [], ...s });

describe("route guards (cosmetic — the wrapper enforces)", () => {
  it("no session → full-document redirect to /bff/login with returnTo", async () => {
    const g = guards(testQueryClient({ authenticated: false }));
    expect(await outcome(g.platform(args("/clients/x?tab=1")))).toEqual({ status: 302, location: "/bff/login?returnTo=%2Fclients%2Fx%3Ftab%3D1", document: true });
  });
  it("home: platform → /dashboard, institution → /institution, neither → /no-access", async () => {
    expect((await outcome(guards(testQueryClient(S({ platformAdmin: true }))).home(args("/"))))?.location).toBe("/dashboard");
    expect((await outcome(guards(testQueryClient(S({ institutionAdmin: true }))).home(args("/"))))?.location).toBe("/institution");
    expect((await outcome(guards(testQueryClient(S({}))).home(args("/"))))?.location).toBe("/no-access");
  });
  it("platform area turns an institution admin back to their console", async () => {
    expect((await outcome(guards(testQueryClient(S({ institutionAdmin: true }))).platform(args("/users"))))?.location).toBe("/institution");
    expect(await outcome(guards(testQueryClient(S({ platformAdmin: true }))).platform(args("/users")))).toBeNull();
  });
  it("institution area admits institution admins and platform admins", async () => {
    expect(await outcome(guards(testQueryClient(S({ institutionAdmin: true }))).institution(args("/institution")))).toBeNull();
    expect(await outcome(guards(testQueryClient(S({ platformAdmin: true }))).institution(args("/institution")))).toBeNull();
    expect((await outcome(guards(testQueryClient(S({}))).institution(args("/institution"))))?.location).toBe("/no-access");
  });
});
