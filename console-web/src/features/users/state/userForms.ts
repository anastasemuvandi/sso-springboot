/**
 * Form rules for the users area — pure (zod), no React.
 *
 * Password: the government-internal realm policy, `length(10) and
 * upperCase(1) and lowerCase(1) and digits(1)`. Keycloak is the enforcer and
 * rejects weaker values; this only explains it next to the field.
 *
 * Exemption reason: MfaPolicy.validateReason — whitespace collapsed, 15..500
 * characters. The wrapper validates it again.
 */
import { z } from "zod";

export const PASSWORD_MIN = 10;
export const REASON_MIN = 15;
export const REASON_MAX = 500;

/** Messages are i18n keys under `users.validation.*`. */
export const passwordSchema = z
  .string()
  .min(PASSWORD_MIN, "users.validation.passwordLength")
  .regex(/[A-Z]/, "users.validation.passwordUpper")
  .regex(/[a-z]/, "users.validation.passwordLower")
  .regex(/[0-9]/, "users.validation.passwordDigit");

export const resetPasswordSchema = z.object({
  password: passwordSchema,
  temporary: z.boolean(),
});
export type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;

/** Whitespace collapsed, as the wrapper records it. */
export function normaliseReason(reason: string): string {
  return reason.trim().split(/\s+/).filter(Boolean).join(" ");
}

export const exemptionSchema = z.object({
  reason: z
    .string()
    .transform(normaliseReason)
    .pipe(z.string().min(REASON_MIN, "users.validation.reasonShort").max(REASON_MAX, "users.validation.reasonLong")),
});
export type ExemptionValues = z.input<typeof exemptionSchema>;

/** Mirrors CreateUserRequest + the console's create flow (password, institution, clients). */
export const createUserSchema = z.object({
  username: z.string().trim().min(3, "users.validation.usernameLength").max(50, "users.validation.usernameLength"),
  email: z.string().trim().email("users.validation.email"),
  firstName: z.string().trim().min(1, "users.validation.required"),
  lastName: z.string().trim().min(1, "users.validation.required"),
  employeeNumber: z.string().trim(),
  password: passwordSchema,
  temporary: z.boolean(),
  agencyId: z.string().min(1, "users.validation.agency"),
  clientIds: z.array(z.string()),
});
export type CreateUserValues = z.infer<typeof createUserSchema>;

/** Realm roles never offered in the assign picker: Keycloak defaults, and `mfa-exempt` (only via exemptions). */
export const HIDDEN_REALM_ROLES = new Set([
  "default-roles-government-internal",
  "default-roles-government-citizens",
  "default-roles-government-admin",
  "offline_access",
  "uma_authorization",
  "mfa-exempt",
]);

export function fullName(u: { firstName?: string | null; lastName?: string | null }): string {
  return [u.firstName, u.lastName].filter(Boolean).join(" ");
}
