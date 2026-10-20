/** Audit service shapes (proxied by the wrapper; the API key is injected server-side). */
export interface AuditEvent {
  id: string;
  event_type: string;
  category: string;
  user_id?: string | null;
  employee_id?: string | null;
  app_id?: string | null;
  realm?: string | null;
  institution?: string | null;
  target?: string | null;
  performed_by?: string | null;
  ip_address?: string | null;
  outcome?: string | null;
  details: Record<string, unknown>;
  severity: string;
  prev_hash: string;
  event_hash: string;
  hash_version?: number | null;
  created_at: string;
}

export interface AuditEventPage {
  total: number;
  limit: number;
  offset: number;
  items: AuditEvent[];
}

export interface AuditEventFilters {
  event_type?: string;
  category?: string;
  user_id?: string;
  app_id?: string;
  realm?: string;
  institution?: string;
  target?: string;
  severity?: string;
  from_date?: string;
  to_date?: string;
  limit?: number;
  offset?: number;
}

export interface AuditIntegrityReport {
  valid: boolean;
  total_checked: number;
  tampered_count: number;
  first_tampered_id?: string | null;
  first_tampered_at?: string | null;
  checked_from?: string | null;
  checked_to?: string | null;
}

export interface AuditRetentionPolicy {
  id: string;
  category: string | null;
  retention_days: number;
  legal_hold: boolean;
  created_at: string;
  updated_at: string;
}

/** One Keycloak realm event (GET /dashboard/events) — the raw, non-tamper-evident stream. */
export interface KeycloakEvent {
  time: number;
  type: string;
  realmId?: string;
  clientId?: string | null;
  userId?: string | null;
  sessionId?: string | null;
  ipAddress?: string | null;
  error?: string | null;
  details?: Record<string, unknown> | null;
}

/** GET /dashboard/activity/24h */
export interface Activity24h {
  logins: number;
  logouts: number;
  registrations: number;
  failed_logins: number;
  total_events: number;
  /** The wrapper read its maximum number of events; counts are a floor. */
  truncated?: boolean;
}

export type ExportFormat = "csv" | "pdf";
