/**
 * MSW server for component tests. Default handlers answer the session as a
 * platform admin; each test adds its own with `server.use(...)`.
 *
 * Paths: the API client calls `/api/<wrapper path>`, so handlers use
 * `api("/client/clients")` → `*\/api/client/clients`.
 */
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

import type { Session } from "@/auth/session";

export const api = (path: string) => `*/api${path}`;

export const platformAdmin: Session = {
  authenticated: true,
  username: "admin@risa.gov.rw",
  name: "Platform Admin",
  roles: ["super_admin"],
  platformAdmin: true,
  institutionAdmin: false,
  institutionSlug: null,
};

export const server = setupServer(http.get("*/bff/session", () => HttpResponse.json(platformAdmin)));

/** The wrapper's error envelope. */
export function envelope(status: number, error: string, message: string, details: Record<string, unknown> = {}) {
  return HttpResponse.json({ error, message, details }, { status });
}
