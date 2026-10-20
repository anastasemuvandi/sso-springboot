/**
 * SMTP form <-> Keycloak `smtpServer` map — pure.
 *
 * GET /realm/email/config returns Keycloak's map where every value is a
 * string ("true", "587"); PUT takes the typed SMTPConfig DTO. The password is
 * never shown: Keycloak answers it masked, so a blank field means "keep the
 * stored one" and the key is left out of the body.
 */
import type { SmtpBody } from "../types";

export interface SmtpFormValues {
  host: string;
  port: number;
  from: string;
  fromDisplayName: string;
  replyTo: string;
  replyToDisplayName: string;
  envelopeFrom: string;
  ssl: boolean;
  starttls: boolean;
  auth: boolean;
  user: string;
  password: string;
}

const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));
const bool = (v: unknown) => v === true || String(v).trim().toLowerCase() === "true";

export function smtpFormFromConfig(c: Record<string, unknown> | null | undefined): SmtpFormValues {
  const cfg = c ?? {};
  const port = parseInt(str(cfg.port), 10);
  return {
    host: str(cfg.host),
    port: Number.isFinite(port) && port > 0 ? port : 25,
    from: str(cfg.from),
    fromDisplayName: str(cfg.fromDisplayName),
    replyTo: str(cfg.replyTo),
    replyToDisplayName: str(cfg.replyToDisplayName),
    envelopeFrom: str(cfg.envelopeFrom),
    ssl: bool(cfg.ssl),
    starttls: bool(cfg.starttls),
    auth: bool(cfg.auth),
    user: str(cfg.user),
    password: "",
  };
}

/** The PUT body. Optional blanks become null; a blank password is omitted (kept). */
export function smtpBody(v: SmtpFormValues): SmtpBody {
  const opt = (s: string) => (s.trim() ? s.trim() : undefined);
  const body: SmtpBody = {
    host: v.host.trim(),
    port: v.port,
    from: v.from.trim(),
    fromDisplayName: opt(v.fromDisplayName),
    replyTo: opt(v.replyTo),
    replyToDisplayName: opt(v.replyToDisplayName),
    envelopeFrom: opt(v.envelopeFrom),
    ssl: v.ssl,
    starttls: v.starttls,
    auth: v.auth,
    user: v.auth ? opt(v.user) : undefined,
  };
  if (v.auth && v.password) body.password = v.password;
  return body;
}
