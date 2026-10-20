/**
 * Shapes of the delegated institution-admin API (`/institution-admin/*`).
 * Ported from the reference console (lib/institution-client.ts). Where the
 * generated schema has a type, prefer `Schemas[...]`; these name the rest.
 */
import type { JoinColumnSetting } from "@/features/clients/types";

/** Delegated capabilities (Keycloak FGAP vocabulary; templates.json). */
export type Capability = "view-members" | "manage-members" | "manage-membership" | "map-roles" | "view-clients" | "manage-clients";

export const CAPABILITIES: readonly Capability[] = ["view-members", "manage-members", "manage-membership", "map-roles", "view-clients", "manage-clients"];

/** GET /institution-admin/me */
export interface InstitutionContext {
  institution: string;
  administered: string[];
  is_platform_admin: boolean;
  actor: string;
  capabilities: Capability[];
}

/** GET /institution-admin/permissions — the caller's resolved scoping. */
export interface InstitutionPermissions {
  institution: string;
  templates: string[];
  capabilities: Capability[];
  members: { group: string; include_subgroups: boolean };
  roles: { allow: string[]; deny: string[] };
  clients: { allow: string[]; deny: string[] };
}

/** A member of the institution (Keycloak user, enriched with role names). */
export interface Member {
  id: string;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  enabled: boolean;
  emailVerified?: boolean;
  createdTimestamp?: number;
  attributes?: Record<string, string[]>;
  requiredActions?: string[];
  /**
   * Direct realm roles (default-roles-* removed). Only on `GET /users/{id}`
   * (InstitutionViews.userDetail); the list (`GET /users`) carries no roles.
   */
  roles?: string[];
  /** Reference-wrapper name for the same list; read as a fallback only. */
  realmRoles?: string[];
  clientRoles?: Record<string, string[]>;
  /** Groups — only on `GET /users/{id}`. */
  agencies?: Array<{ id: string; name: string; path?: string }>;
}

/** A member's realm roles, whichever name the wrapper used. */
export function memberRoles(m: Pick<Member, "roles" | "realmRoles">): string[] {
  return m.roles ?? m.realmRoles ?? [];
}

export interface AssignableRole {
  id: string;
  name: string;
  description?: string | null;
  composite?: boolean;
  clientRole?: boolean;
}

/** An app this institution may use: its own `<slug>-<app>` or a cross-cutting one. */
export interface InstitutionApp {
  id: string;
  clientId: string;
  name?: string | null;
  manageable: boolean;
  cross_cutting: boolean;
  owned: boolean;
  scope: {
    kind: "institution" | "cross-cutting" | "platform";
    owner_institution: string | null;
    cross_cutting: boolean;
    all_institutions: boolean;
    institutions: string[];
  };
  join_column: JoinColumnSetting;
  mfa_required: boolean;
  mfa_applies: boolean | null;
  mfa_mode: "off" | "per_client" | "all" | null;
}
