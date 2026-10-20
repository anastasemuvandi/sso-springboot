/**
 * Role matching for guards. A required role may be:
 *  - an exact realm role: `inst:minagri:viewer`, `admin`
 *  - an institution wildcard: `inst:minagri:*` (any role in that institution)
 *  - a client role: `client:editor` (this app's `resource_access.<clientId>.roles`)
 *
 * Client-side checks are COSMETIC: the API enforces every rule again on every call.
 */
import type { Session } from "./types";

export function hasRole(session: Session | null | undefined, required: string): boolean {
  if (!session?.authenticated || !required) return false;
  const realm = session.roles ?? [];
  if (required.startsWith("client:")) {
    return (session.clientRoles ?? []).includes(required.slice("client:".length));
  }
  if (required.endsWith(":*") && required.startsWith("inst:")) {
    const prefix = required.slice(0, -1); // keep the trailing ':'
    return realm.some((r) => r.toLowerCase().startsWith(prefix.toLowerCase()) && r.length > prefix.length);
  }
  return realm.includes(required);
}

export function hasAnyRole(session: Session | null | undefined, required: string[]): boolean {
  return required.some((r) => hasRole(session, r));
}

/** `inst:<slug>:<role>` roles grouped by slug. */
export function institutionRoles(session: Session | null | undefined): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const r of session?.roles ?? []) {
    const m = /^inst:([A-Za-z0-9-]+):(.+)$/.exec(r);
    if (m) (out[m[1]!.toLowerCase()] ??= []).push(m[2]!);
  }
  return out;
}
