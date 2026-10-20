/** Read (NOT verify) a JWT payload. Signatures are the resource server's job. */
export type JwtPayload = Record<string, unknown>;

export function decodeJwtPayload(token: string): JwtPayload {
  const parts = token.split(".");
  if (parts.length < 2 || !parts[1]) throw new Error("not a JWT");
  const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
  const bin = atob(padded);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  const json = new TextDecoder().decode(bytes);
  const payload: unknown = JSON.parse(json);
  if (!payload || typeof payload !== "object") throw new Error("JWT payload is not an object");
  return payload as JwtPayload;
}

export function claimString(p: JwtPayload, name: string): string | null {
  const v = p[name];
  return typeof v === "string" && v.length > 0 ? v : null;
}

export function realmRoles(p: JwtPayload): string[] {
  const ra = p["realm_access"];
  if (ra && typeof ra === "object" && Array.isArray((ra as { roles?: unknown }).roles)) {
    return ((ra as { roles: unknown[] }).roles).filter((r): r is string => typeof r === "string");
  }
  return [];
}

export function clientRoles(p: JwtPayload, clientId: string): string[] {
  const ra = p["resource_access"];
  if (ra && typeof ra === "object") {
    const c = (ra as Record<string, unknown>)[clientId];
    if (c && typeof c === "object" && Array.isArray((c as { roles?: unknown }).roles)) {
      return ((c as { roles: unknown[] }).roles).filter((r): r is string => typeof r === "string");
    }
  }
  return [];
}
