/**
 * Realm-role rules — pure. Keycloak's own roles are listed but never offered
 * for deletion (the wrapper/Keycloak would refuse or break the realm), and
 * `mfa-exempt` / `inst:<slug>:*` are flagged because they are managed by
 * dedicated flows (MFA exemptions, onboarding-as-code).
 */
export const KEYCLOAK_BUILTIN_ROLES = new Set([
  "default-roles-government-internal",
  "default-roles-government-citizens",
  "default-roles-government-admin",
  "offline_access",
  "uma_authorization",
]);

export const PLATFORM_ADMIN_ROLES = new Set(["super_admin", "admin", "system_admin"]);

export type RoleKind = "builtin" | "platform_admin" | "institution" | "mfa_exempt" | "custom";

export function isBuiltinRole(name: string): boolean {
  return KEYCLOAK_BUILTIN_ROLES.has(name) || name.startsWith("default-roles-");
}

export function roleKind(name: string): RoleKind {
  if (isBuiltinRole(name)) return "builtin";
  if (PLATFORM_ADMIN_ROLES.has(name)) return "platform_admin";
  if (name.startsWith("inst:")) return "institution";
  if (name === "mfa-exempt") return "mfa_exempt";
  return "custom";
}

/** Lowercase snake_case, or the `inst:<slug>:<role>` namespace. */
export const ROLE_NAME_PATTERN = /^(inst:[a-z0-9-]+:)?[a-z][a-z0-9_-]*$/;

export function sortRoles<T extends { name: string }>(roles: readonly T[]): T[] {
  const rank: Record<RoleKind, number> = { platform_admin: 0, custom: 1, institution: 2, mfa_exempt: 3, builtin: 4 };
  return [...roles].sort((a, b) => rank[roleKind(a.name)] - rank[roleKind(b.name)] || a.name.localeCompare(b.name));
}
