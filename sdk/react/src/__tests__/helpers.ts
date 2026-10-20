import { base64UrlEncode } from "../pkce";

export function b64urlJson(o: unknown): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(o)));
}

/** An unsigned JWT-shaped token (the SDK never verifies signatures; the API does). */
export function fakeJwt(payload: Record<string, unknown>): string {
  return `${b64urlJson({ alg: "RS256", typ: "JWT" })}.${b64urlJson(payload)}.c2ln`;
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export const now = () => Math.floor(Date.now() / 1000);

export function accessToken(extra: Record<string, unknown> = {}): string {
  return fakeJwt({
    iss: "http://localhost:8080/realms/government-internal",
    azp: "minagri-kubaka-web",
    exp: now() + 300,
    preferred_username: "jdoe",
    join_key: "1199080012345678",
    institution: "minagri",
    realm_access: { roles: ["inst:minagri:staff", "offline_access"] },
    resource_access: { "minagri-kubaka-web": { roles: ["viewer"] } },
    ...extra,
  });
}
