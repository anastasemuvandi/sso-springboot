/**
 * Staff (institution member) forms and role splitting — pure, no React.
 *
 * NID / phone are the identifiers an application may authenticate on (its
 * join key). The wrapper checks them on every write (400 malformed, 409 held
 * by another account); the same shapes are checked here only so the admin
 * sees the problem next to the field before the round trip. Shapes mirror the
 * wrapper's join-key specs: separators `[\s\-()./]` are ignored, NID = exactly
 * 16 digits, phone = 9–15 digits with an optional leading '+'.
 */
import { z } from "zod";

import { ApiError } from "@/api/errors";
import { memberRoles, type AssignableRole, type Member } from "../../types";

const SEPARATORS = /[\s\-()./]/g;
export const NID_DIGITS = 16;

export function cleanIdentifier(value: string | null | undefined): string {
  return (value ?? "").replace(SEPARATORS, "");
}

export function isValidNid(value: string): boolean {
  return new RegExp(`^\\d{${NID_DIGITS}}$`).test(cleanIdentifier(value));
}

export function isValidPhone(value: string): boolean {
  return /^\+?\d{9,15}$/.test(cleanIdentifier(value));
}

const optionalNid = z
  .string()
  .trim()
  .refine((v) => v === "" || isValidNid(v), { message: "nid" });
const optionalPhone = z
  .string()
  .trim()
  .refine((v) => v === "" || isValidPhone(v), { message: "phone" });

/** Error messages are i18n keys under `instStaff.validation.*`. */
export const createStaffSchema = z.object({
  username: z.string().trim().min(3, "usernameShort").max(50, "usernameLong"),
  email: z.string().trim().email("email"),
  firstName: z.string().trim().min(1, "required").max(255),
  lastName: z.string().trim().min(1, "required").max(255),
  national_id: optionalNid,
  phone: optionalPhone,
});
export type CreateStaffValues = z.infer<typeof createStaffSchema>;

export const updateStaffSchema = z.object({
  email: z.string().trim().email("email"),
  firstName: z.string().trim().min(1, "required").max(255),
  lastName: z.string().trim().min(1, "required").max(255),
  emailVerified: z.boolean(),
  national_id: optionalNid,
  phone: optionalPhone,
});
export type UpdateStaffValues = z.infer<typeof updateStaffSchema>;

/**
 * POST /institution-admin/users body. The institution is never a field: the
 * wrapper pins the account (attribute + group) to the caller's institution.
 * Accounts are created enabled and email-verified, as in the reference.
 */
export function createStaffBody(v: CreateStaffValues) {
  return {
    username: v.username.trim(),
    email: v.email.trim(),
    firstName: v.firstName.trim(),
    lastName: v.lastName.trim(),
    enabled: true,
    emailVerified: true,
    national_id: v.national_id.trim() || undefined,
    phone: v.phone.trim() || undefined,
  };
}

/**
 * PATCH body. NID / phone are sent only when they carry a value: the wrapper
 * clears an attribute written empty, and an untouched blank field must not
 * wipe a National ID the IPPIS reconcile sourced.
 */
export function updateStaffBody(v: UpdateStaffValues) {
  return {
    email: v.email.trim(),
    firstName: v.firstName.trim(),
    lastName: v.lastName.trim(),
    emailVerified: v.emailVerified,
    national_id: v.national_id.trim() || undefined,
    phone: v.phone.trim() || undefined,
  };
}

export function updateDefaults(m: Member): UpdateStaffValues {
  return {
    email: m.email ?? "",
    firstName: m.firstName ?? "",
    lastName: m.lastName ?? "",
    emailVerified: Boolean(m.emailVerified),
    national_id: m.attributes?.national_id?.[0] ?? "",
    phone: m.attributes?.phone?.[0] ?? "",
  };
}

export type IdentifierField = "national_id" | "phone" | "email" | "username";

const FIELD_ALIASES: Record<string, IdentifierField> = {
  national_id: "national_id",
  nationalId: "national_id",
  phone: "phone",
  email: "email",
  username: "username",
};

/**
 * Which form field a wrapper refusal is about:
 * - 400 `identifier_malformed` / 409 `identifier_taken`: `details.field` is the
 *   attribute (`national_id`, `phone`, `email`);
 * - 422 `validation_error` (bean validation): `details.fields[{field, message}]`,
 *   where `field` is the Java property (`nationalId`);
 * - otherwise the attribute named in the message. Null = not attributable.
 */
export function identifierErrorField(e: unknown): IdentifierField | null {
  if (!(e instanceof ApiError) || (e.status !== 400 && e.status !== 409 && e.status !== 422)) return null;
  const listed = Array.isArray(e.details.fields) ? (e.details.fields as Array<{ field?: unknown }>)[0]?.field : undefined;
  const f = e.details.field ?? e.details.attribute ?? listed;
  if (typeof f === "string" && FIELD_ALIASES[f]) return FIELD_ALIASES[f];
  const m = e.message.toLowerCase();
  if (m.includes("national_id") || m.includes("national id") || /\bnid\b/.test(m)) return "national_id";
  if (m.includes("'phone'") || m.includes("phone")) return "phone";
  if (m.includes("username")) return "username";
  if (m.includes("email")) return "email";
  return null;
}

export function fullName(m: Pick<Member, "firstName" | "lastName">): string {
  return [m.firstName, m.lastName].filter(Boolean).join(" ").trim();
}

// ---------------------------------------------------------------------------
// List filtering
// ---------------------------------------------------------------------------

export const STATUS_VALUES = ["all", "enabled", "disabled"] as const;
export type StaffStatus = (typeof STATUS_VALUES)[number];

export function parseStatus(v: string | null | undefined): StaffStatus {
  return (STATUS_VALUES as readonly string[]).includes(v ?? "") ? (v as StaffStatus) : "all";
}

/**
 * Status is filtered in the console: the endpoint takes only `search`, and the
 * set is already bounded to one institution server-side, so filtering locally
 * cannot leak anything or miss a page.
 */
export function filterMembers(members: Member[], status: StaffStatus): Member[] {
  return members
    .filter((m) => (status === "all" ? true : status === "enabled" ? m.enabled : !m.enabled))
    .sort((a, b) => a.username.localeCompare(b.username));
}

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

/** The institution's role namespace: `inst:<slug>:`. */
export function namespaceOf(slug: string): string {
  return `inst:${slug}:`;
}

export function inNamespace(role: string, slug: string): boolean {
  return role.startsWith(namespaceOf(slug));
}

export interface RoleSplit {
  /** Assignable roles the member holds — revocable here. */
  managed: AssignableRole[];
  /** Assignable roles the member does not hold — grantable here. */
  available: AssignableRole[];
  /** `inst:<slug>:*` roles held but not assignable by this tier (e.g. denied) — read-only. */
  heldNotAssignable: string[];
  /** How many realm roles outside the namespace the account holds (not listed). */
  outsideCount: number;
}

/**
 * Split a member's roles for the Roles screen. Only `inst:<slug>:*` roles are
 * ever offered or listed: the assignable set from GET /institution-admin/roles
 * is already filtered by the predicate that guards the write, and is filtered
 * again to the namespace here so a misconfigured template can never make this
 * screen offer a platform role.
 */
export function splitRoles(slug: string, held: readonly string[], assignable: readonly AssignableRole[]): RoleSplit {
  const heldSet = new Set(held);
  const offer = assignable.filter((r) => inNamespace(r.name, slug)).sort((a, b) => a.name.localeCompare(b.name));
  const offerNames = new Set(offer.map((r) => r.name));
  return {
    managed: offer.filter((r) => heldSet.has(r.name)),
    available: offer.filter((r) => !heldSet.has(r.name)),
    heldNotAssignable: held.filter((r) => inNamespace(r, slug) && !offerNames.has(r)).sort(),
    outsideCount: held.filter((r) => !inNamespace(r, slug)).length,
  };
}

/**
 * Role name → usernames holding it, from the member list — or null when the
 * list carries no roles (the Spring wrapper's `GET /institution-admin/users`
 * returns none; only the per-user read does), so the page shows "—" rather
 * than claiming nobody holds a role.
 */
export function roleHolders(members: readonly Member[]): Map<string, string[]> | null {
  if (!members.some((m) => Array.isArray(m.roles) || Array.isArray(m.realmRoles))) return members.length === 0 ? new Map() : null;
  const out = new Map<string, string[]>();
  for (const m of members) {
    for (const r of memberRoles(m)) {
      const list = out.get(r) ?? [];
      list.push(m.username);
      out.set(r, list);
    }
  }
  return out;
}
