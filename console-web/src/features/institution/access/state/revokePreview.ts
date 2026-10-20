/**
 * Revoke flows — pure, no React. Builds the request bodies for both flows
 * (client-first: one app, chosen holders; user-first: chosen users, chosen
 * apps or ALL), and shapes the server's PREVIEW into what the confirmation
 * shows. The numbers an admin agrees to come from `preview: true` — never
 * from console arithmetic.
 *
 * "All applications" is the destructive option: the wrapper answers a request
 * without `confirm_all_clients` as a preview and changes nothing; the console
 * additionally asks for a typed phrase. The phrase is the console's warning,
 * not the enforcement.
 */
import type { ClientHolders, RevokeReport, RevokeRequestBody, RevokeUserResult } from "../types";

/** RevokeRequest.user_ids maxItems. */
export const MAX_REVOKE_USERS = 500;
export const CONFIRM_PHRASE = "REMOVE ALL";

export type RevokeBodyError = "no-users" | "too-many-users" | "no-clients" | "phrase";

export type RevokeBody = { ok: true; body: RevokeRequestBody } | { ok: false; error: RevokeBodyError };

function users(ids: readonly string[]): string[] | RevokeBodyError {
  const unique = [...new Set(ids.map((s) => s.trim()).filter(Boolean))];
  if (unique.length === 0) return "no-users";
  if (unique.length > MAX_REVOKE_USERS) return "too-many-users";
  return unique;
}

export function phraseMatches(typed: string): boolean {
  return typed.trim() === CONFIRM_PHRASE;
}

/**
 * User-first (flow B). `preview` asks the server what would happen; the real
 * call for all clients needs the typed phrase and then sends
 * `confirm_all_clients: true`.
 */
export function userFirstBody(opts: {
  userIds: readonly string[];
  clientIds: readonly string[];
  allClients: boolean;
  preview: boolean;
  typedPhrase?: string;
}): RevokeBody {
  const u = users(opts.userIds);
  if (typeof u === "string") return { ok: false, error: u };
  if (opts.allClients) {
    if (opts.preview) return { ok: true, body: { user_ids: u, all_clients: true, preview: true } };
    if (!phraseMatches(opts.typedPhrase ?? "")) return { ok: false, error: "phrase" };
    return { ok: true, body: { user_ids: u, all_clients: true, confirm_all_clients: true } };
  }
  const clients = [...new Set(opts.clientIds.filter(Boolean))];
  if (clients.length === 0) return { ok: false, error: "no-clients" };
  return { ok: true, body: opts.preview ? { user_ids: u, client_ids: clients, preview: true } : { user_ids: u, client_ids: clients } };
}

/** Client-first (flow A): one application, the chosen holders. */
export function clientFirstBody(clientId: string, userIds: readonly string[], preview: boolean): RevokeBody {
  const u = users(userIds);
  if (typeof u === "string") return { ok: false, error: u };
  if (!clientId) return { ok: false, error: "no-clients" };
  return { ok: true, body: preview ? { user_ids: u, client_ids: [clientId], preview: true } : { user_ids: u, client_ids: [clientId] } };
}

/** Only the chosen apps the server says the caller may revoke. */
export function revocableSelection(chosen: readonly string[], held: readonly ClientHolders[]): string[] {
  const ok = new Set(held.filter((c) => c.revocable).map((c) => c.client_id));
  return chosen.filter((c) => ok.has(c));
}

export interface RevokeSummaryData {
  totalRevocations: number;
  usersAffected: number;
  clientsConsidered: number;
  totalSkipped: number;
  /** Users who lose at least one application. */
  losing: RevokeUserResult[];
  /** Users with nothing to remove — shown, not filtered out. */
  untouched: RevokeUserResult[];
  /** Nothing would change: disable the confirm button. */
  nothingToDo: boolean;
}

/** The confirmation data, straight from the server's report. */
export function summarizeRevoke(report: RevokeReport): RevokeSummaryData {
  return {
    totalRevocations: report.total_revocations,
    usersAffected: report.users_affected,
    clientsConsidered: report.clients_considered.length,
    totalSkipped: report.total_skipped,
    losing: report.users.filter((u) => u.revoked.length > 0),
    untouched: report.users.filter((u) => u.revoked.length === 0),
    nothingToDo: report.total_revocations === 0,
  };
}

/** May the destructive confirm button be pressed? */
export function canConfirm(report: RevokeReport | null | undefined, allClients: boolean, typedPhrase: string): boolean {
  if (!report || report.total_revocations === 0) return false;
  return allClients ? phraseMatches(typedPhrase) : true;
}
