import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { ShieldAlert, ShieldCheck, ShieldQuestion } from "lucide-react";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatWhen } from "@/lib/utils";
import { integrityQuery } from "../api";
import { integrityKind } from "../state/auditFilters";

/**
 * Hash-chain verification. Runs once over the whole chain on load; an admin
 * can re-run it over a date window. A failure to verify is shown as such —
 * never as "intact".
 */
export function IntegrityBanner() {
  const { t } = useTranslation();
  const [range, setRange] = useState<{ from_date?: string; to_date?: string }>({});
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const q = useQuery(integrityQuery(range));

  function onVerify(e: FormEvent) {
    e.preventDefault();
    const next = { ...(from ? { from_date: from } : {}), ...(to ? { to_date: to } : {}) };
    if (JSON.stringify(next) === JSON.stringify(range)) void q.refetch();
    else setRange(next);
  }

  let banner: JSX.Element;
  if (q.isPending || q.isFetching) {
    banner = (
      <div className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
        <ShieldQuestion className="h-4 w-4" />
        {t("audit.integrity.verifying")}
      </div>
    );
  } else if (q.isError) {
    banner = (
      <div className="flex items-start gap-2 rounded-md border border-amber-500/50 bg-amber-500/10 px-3 py-2 text-sm text-amber-700 dark:text-amber-400" data-testid="integrity-error">
        <ShieldQuestion className="mt-0.5 h-4 w-4 shrink-0" />
        {t("audit.integrity.couldNotVerify", { error: errorMessage(q.error) })}
      </div>
    );
  } else {
    const r = q.data;
    const kind = integrityKind(r);
    banner =
      kind === "tampered" ? (
        <div className="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive" data-testid="integrity-tampered">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          {t("audit.integrity.tampered", {
            bad: r.tampered_count,
            total: r.total_checked.toLocaleString(),
            id: r.first_tampered_id ?? "?",
            at: r.first_tampered_at ? formatWhen(r.first_tampered_at) : "—",
          })}
        </div>
      ) : kind === "empty" ? (
        <div className="flex items-start gap-2 rounded-md border px-3 py-2 text-sm text-muted-foreground" data-testid="integrity-empty">
          <ShieldQuestion className="mt-0.5 h-4 w-4 shrink-0" />
          {t("audit.integrity.empty")}
        </div>
      ) : (
        <div className="flex items-start gap-2 rounded-md border border-emerald-500/50 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-700 dark:text-emerald-400" data-testid="integrity-valid">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
          {t("audit.integrity.valid", { total: r.total_checked.toLocaleString() })}
        </div>
      );
  }

  return (
    <section className="space-y-3 rounded-xl border bg-card p-5 shadow-card">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">{t("audit.integrity.title")}</h2>
          <p className="text-xs text-muted-foreground">
            {range.from_date || range.to_date
              ? t("audit.integrity.window", { from: range.from_date ?? "…", to: range.to_date ?? "…" })
              : t("audit.integrity.wholeChain")}
          </p>
        </div>
        <form onSubmit={onVerify} className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
            {t("audit.filters.from")}
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-[150px]" />
          </label>
          <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
            {t("audit.filters.to")}
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-[150px]" />
          </label>
          <Button type="submit" size="sm" variant="secondary" disabled={q.isFetching} data-testid="integrity-verify">
            {t("audit.integrity.verify")}
          </Button>
        </form>
      </div>
      {banner}
    </section>
  );
}
