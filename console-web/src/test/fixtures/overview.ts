import type { Overview } from "@/features/overview/types";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type SectionOverrides = { [K in keyof Overview]?: Overview[K] extends object ? Record<string, any> : Overview[K] | null };

/** A fully-available overview with every count at zero; sections are shallow-merged. */
export function zeroOverview(over: SectionOverrides = {}): Overview {
  const base = {
    institution: null,
    generated_at: "2026-10-20T10:00:00Z",
    members: 0,
    leavers: { available: true, error: null, applications_checked: true, total: 0, truncated: false, items: [] },
    password_setup: { available: true, error: null, counts: { total: 0, sent: 0, failed: 0, not_configured: 0, unknown: 0 }, items: [] },
    logins: {
      available: true,
      error: null,
      logins_24h: 0,
      logins_7d: 0,
      failed_24h: 0,
      failed_7d: 0,
      people_24h: 0,
      people_7d: 0,
      most_failures_7d: [],
      locked_now: [],
      truncated: false,
      active_sessions: 0,
    },
    access: { available: true, error: null, items: [] },
  } as unknown as Overview;
  const out: Record<string, unknown> = { ...base };
  for (const [k, v] of Object.entries(over)) {
    const cur = (base as Record<string, unknown>)[k];
    out[k] = cur && typeof cur === "object" && v && typeof v === "object" ? { ...cur, ...v } : v;
  }
  return out as Overview;
}
