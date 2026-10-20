/** GET /institution-admin/audit — one institution's slice of the hash-chained trail. */
export interface InstitutionAuditEvent {
  id: string;
  event_type: string;
  category: string;
  institution?: string | null;
  target?: string | null;
  performed_by?: string | null;
  user_id?: string | null;
  app_id?: string | null;
  ip_address?: string | null;
  details: Record<string, unknown>;
  severity: string;
  prev_hash: string;
  event_hash: string;
  hash_version?: number;
  created_at: string;
}

export interface InstitutionAuditPage {
  total: number;
  limit: number;
  offset: number;
  items: InstitutionAuditEvent[];
  /**
   * Present only when the wrapper dropped events the audit service returned
   * outside this institution (the downstream filter was not applied) — a
   * deployment problem the console surfaces.
   */
  scope_filtered_out?: number;
}
