/**
 * Shapes of the access-matrix, import and revoke endpoints under
 * `/institution-admin/*` (ported from the reference console's
 * lib/institution-client.ts and lib/access-matrix-transfer.ts).
 */
import type { Mode, MatrixColumn, MatrixRow, BulkResult } from "./state/matrixState";

export type { Mode, MatrixColumn, MatrixRow, BulkResult };

/** GET /institution-admin/access-matrix */
export interface AccessMatrixPage {
  institution: string;
  columns: MatrixColumn[];
  items: MatrixRow[];
  page: number;
  page_size: number;
  /** Rows matching the search and filter. */
  total: number;
  /** Everyone in the institution's employee list. */
  total_employees: number;
  /** Per client: matching rows with access. */
  counts: Record<string, number>;
}

export type AccessFilter = "" | "has" | "none";
export type GenderFilter = "" | "M" | "F" | "unknown";

export interface AccessMatrixQuery {
  clients: string[];
  page: number;
  page_size: number;
  q?: string;
  access?: AccessFilter;
  access_client?: string;
  gender?: GenderFilter;
}

/** POST /institution-admin/grants/bulk */
export interface BulkAccessReport {
  institution: string;
  action: Mode;
  channel: string;
  batch_id: string;
  results: BulkResult[];
  summary: Partial<Record<"applied" | "rejected" | "failed", number>>;
}

/** POST /institution-admin/access-matrix/setup-email/{n} */
export interface SetupEmailResend {
  employee_number: string;
  sub: string;
  status: "sent" | "failed" | "not_configured";
  message: string;
}

// ---- spreadsheet import ----

export type ImportOutcome = "will_grant" | "will_create_account" | "already_has_access" | "removal_ignored" | "rejected" | "invalid";

export interface ImportItem {
  row: number | null;
  employee_number: string | null;
  name: string | null;
  client_id: string | null;
  outcome: ImportOutcome;
  reason: string | null;
  message: string | null;
}

/** POST /institution-admin/access-matrix/import — the preview; writes nothing. */
export interface ImportPreview {
  /** Null when the file grants nothing (nothing to confirm). */
  import_id: string | null;
  expires_at: string | null;
  institution: string;
  approved_by: string;
  file_name: string | null;
  file_sha256: string;
  export_id: string;
  exported_at: string;
  exported_by: string;
  clients: string[];
  summary: Partial<Record<ImportOutcome, number>>;
  items: ImportItem[];
}

/** POST /institution-admin/access-matrix/import/{id}/apply */
export interface ImportReport extends BulkAccessReport {
  import_id: string;
  approved_by: string;
  file_name: string | null;
}

// ---- revoke ----

export interface AccessHolder {
  id: string;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  enabled: boolean;
  direct: boolean;
  via_groups: string[];
}

/** GET /institution-admin/apps/{clientId}/users */
export interface ClientUsers {
  client_id: string;
  name?: string | null;
  institution: string;
  cross_cutting: boolean;
  revocable: boolean;
  total: number;
  items: AccessHolder[];
}

export interface ClientHolders {
  client_id: string;
  name?: string | null;
  cross_cutting: boolean;
  revocable: boolean;
  user_ids: string[];
  holders: number;
}

/** POST /institution-admin/access/clients-for-users */
export interface UserClients {
  institution: string;
  clients: ClientHolders[];
  by_user: Record<string, string[]>;
  usernames: Record<string, string>;
  total_pairs: number;
}

export interface RevokeSkip {
  client_id: string;
  reason: string;
  message?: string | null;
}

export interface RevokeUserResult {
  user_id: string;
  username?: string | null;
  revoked: string[];
  skipped: RevokeSkip[];
}

/** POST /institution-admin/access/revoke — outcome or preview (same shape). */
export interface RevokeReport {
  institution: string;
  preview: boolean;
  all_clients: boolean;
  clients_considered: string[];
  users: RevokeUserResult[];
  total_revocations: number;
  total_skipped: number;
  users_affected: number;
}

export interface RevokeRequestBody {
  user_ids: string[];
  client_ids?: string[];
  all_clients?: boolean;
  confirm_all_clients?: boolean;
  preview?: boolean;
}
