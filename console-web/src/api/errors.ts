/**
 * The wrapper's error envelope, `{ error, message, details }`, as a typed
 * Error. Every failed API call surfaces as an `ApiError`.
 *
 * The reference (FastAPI) wrapper answered `{ detail: ... }`; that shape is
 * still understood so a page keeps a readable message against either backend.
 */
export interface ErrorEnvelope {
  error: string;
  message: string;
  details?: Record<string, unknown> | null;
}

export class ApiError extends Error {
  readonly name = "ApiError";
  constructor(
    /** HTTP status; 0 for a network failure. */
    readonly status: number,
    /** Machine code from the envelope (`access_denied`, `state_changed`, ...), or `http_<status>`. */
    readonly code: string,
    message: string,
    /** The envelope's `details` object (always an object, possibly empty). */
    readonly details: Record<string, unknown> = {},
    /** The raw parsed body, for debugging. */
    readonly body?: unknown,
  ) {
    super(message);
  }

  get isConflict() {
    return this.status === 409;
  }
  get isNotFound() {
    return this.status === 404;
  }
  get isForbidden() {
    return this.status === 403;
  }
  get isUnauthorized() {
    return this.status === 401;
  }
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
}

/** Build an ApiError from a status and whatever body came back. */
export function toApiError(status: number, body: unknown, statusText = ""): ApiError {
  const rec = asRecord(body);
  if (rec && typeof rec.error === "string") {
    const message = typeof rec.message === "string" && rec.message ? rec.message : rec.error;
    return new ApiError(status, rec.error, message, asRecord(rec.details) ?? {}, body);
  }
  // Legacy FastAPI shapes: { detail: "msg" } | { detail: { message, ... } } | { detail: [{ msg }] }
  if (rec && "detail" in rec) {
    const d = rec.detail;
    if (typeof d === "string") return new ApiError(status, `http_${status}`, d, {}, body);
    const dr = asRecord(d);
    if (dr) {
      const code = typeof dr.error === "string" ? dr.error : typeof dr.reason === "string" ? dr.reason : `http_${status}`;
      const msg = typeof dr.message === "string" ? dr.message : JSON.stringify(dr);
      return new ApiError(status, code, msg, dr, body);
    }
    if (Array.isArray(d)) {
      const msgs = d.map((i) => (asRecord(i)?.msg as string | undefined) ?? String(i)).filter(Boolean);
      return new ApiError(status, "validation_failed", msgs.join("; "), { errors: d }, body);
    }
  }
  if (typeof body === "string" && body.trim()) {
    return new ApiError(status, `http_${status}`, body.slice(0, 300), {}, body);
  }
  return new ApiError(status, `http_${status}`, statusText || `Request failed (${status})`, {}, body);
}

/** Human message for anything thrown by a query or mutation. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.status ? `${e.message} (${e.status})` : e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
