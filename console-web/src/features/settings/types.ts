/** Realm settings shapes. DTO bodies come from the generated schema. */
import type { RequestSchemas, Schemas } from "@/api/types";

export type RealmConfigBody = RequestSchemas["RealmConfig"];
export type BrandingBody = RequestSchemas["BrandingConfig"];
export type SmtpBody = RequestSchemas["SMTPConfig"];
export type SmtpTestResult = Schemas["SMTPTestResult"];
export type ThemesResponse = Schemas["ThemesResponse"];

/** GET /realm/config — the Keycloak realm representation (many more fields than RealmConfig). */
export type RealmRepresentation = Record<string, unknown>;

/** One entry of GET /realm/mfa/exemptions → { exemptions: [...] } */
export interface MfaExemption {
  user_id: string;
  username?: string | null;
  reason?: string | null;
  granted_by?: string | null;
  granted_at?: string | null;
}

/** GET /realm/branding (RealmSettingsService.branding). */
export interface BrandingState {
  themes: { login: string | null; admin: string | null; account: string | null; email: string | null };
  display: { name: string | null; html_name: string | null };
  /** logo_url, primary_color, secondary_color, background_color, favicon_url, welcome_title, welcome_message */
  custom_attributes: Record<string, string | null>;
}
