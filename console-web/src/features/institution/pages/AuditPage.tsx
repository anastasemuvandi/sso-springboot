/**
 * /institution/audit — this institution's hash-chained audit trail.
 *
 * The institution is not a filter the page controls: the wrapper pins it to
 * the caller's resolved context and re-filters the response. When it had to
 * drop events outside the institution (`scope_filtered_out`), we say so.
 * Gated on view-members, like the endpoint.
 */
import { useState, type FormEvent } from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import { institutionAuditQuery } from "../audit/api";
import {
  QUICK_FILTERS,
  SEVERITIES,
  appOf,
  display,
  eventKey,
  isFiltered,
  parseFilters,
  severityVariant,
  summaryParts,
  targetOf,
  toSearch,
  knownPages,
  type AuditFilters,
  KNOWN_ACTIONS,
} from "../audit/state/auditView";
import type { InstitutionAuditEvent } from "../audit/types";

const GRID = "grid grid-cols-[150px_150px_180px_1fr_150px_80px_20px] gap-3";

function EventRow({ e }: { e: InstitutionAuditEvent }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const target = targetOf(e);
  const parts = summaryParts(e);
  const summary = [parts.action ? (KNOWN_ACTIONS.has(parts.action) ? t(`instAudit.action.${parts.action}`) : parts.action.replace(/_/g, " ")) : null, ...parts.rest]
    .filter(Boolean)
    .join(" · ");
  const key = eventKey(e.event_type);
  const details = Object.entries(e.details ?? {}).filter(([, v]) => v !== null && v !== undefined && v !== "");
  return (
    <div data-testid="audit-row">
      <button type="button" aria-expanded={open} onClick={() => setOpen((o) => !o)} className={`${GRID} w-full items-center px-4 py-2 text-left text-sm hover:bg-accent`}>
        <span className="whitespace-nowrap font-mono text-xs text-muted-foreground">{e.created_at.replace("T", " ").slice(0, 19)}</span>
        <span className="truncate font-mono text-xs">{e.performed_by ?? e.user_id ?? "—"}</span>
        <span className="min-w-0">
          <span className="block truncate text-xs font-medium">{key ? t(`instAudit.event.${key}`) : e.event_type}</span>
          {summary ? <span className="block truncate text-xs text-muted-foreground">{summary}</span> : null}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-xs">{target.name ?? target.id ?? "—"}</span>
          {target.name && target.id ? <span className="block truncate font-mono text-[11px] text-muted-foreground">{target.id}</span> : null}
        </span>
        <span className="truncate font-mono text-xs">{appOf(e) ?? "—"}</span>
        <span className="text-right">
          <Badge variant={severityVariant(e.severity)}>{e.severity.toLowerCase()}</Badge>
        </span>
        <ChevronRight className={`h-4 w-4 text-muted-foreground transition-transform ${open ? "rotate-90" : ""}`} />
      </button>
      {open ? (
        <div className="space-y-3 bg-muted/30 px-4 py-3">
          <dl className="grid grid-cols-1 gap-x-6 gap-y-1 text-xs sm:grid-cols-[180px_1fr]">
            <dt className="text-muted-foreground">{t("instAudit.eventType")}</dt>
            <dd className="font-mono">{e.event_type}</dd>
            {details.map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted-foreground">{k}</dt>
                <dd className="break-all font-mono">{display(v)}</dd>
              </div>
            ))}
            {e.ip_address ? (
              <>
                <dt className="text-muted-foreground">{t("instAudit.ip")}</dt>
                <dd className="font-mono">{e.ip_address}</dd>
              </>
            ) : null}
          </dl>
          <p className="font-mono text-[11px] text-muted-foreground">{t("instAudit.record", { id: e.id, hash: e.event_hash.slice(0, 16) })}</p>
        </div>
      ) : null}
    </div>
  );
}

function Audit() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [sp, setSp] = useSearchParams();
  const filters = parseFilters(sp);
  const q = useQuery(institutionAuditQuery(inst.scope, filters));
  const [draft, setDraft] = useState({ target: filters.target ?? "", severity: filters.severity ?? "", from: filters.from ?? "", to: filters.to ?? "" });

  const go = (next: Partial<AuditFilters>) => {
    const params = new URLSearchParams();
    if (inst.scope.institution) params.set("institution", inst.scope.institution);
    for (const [k, v] of Object.entries(toSearch({ ...filters, ...next }))) if (v) params.set(k, v);
    setSp(params);
  };
  const onFilter = (e: FormEvent) => {
    e.preventDefault();
    go({ target: draft.target.trim() || undefined, severity: draft.severity || undefined, from: draft.from || undefined, to: draft.to || undefined, page: 1 });
  };
  const reset = () => {
    setDraft({ target: "", severity: "", from: "", to: "" });
    setSp(inst.scope.institution ? new URLSearchParams({ institution: inst.scope.institution }) : new URLSearchParams());
  };

  const data = q.data;
  const pages = data ? knownPages(data.total, filters.page, data.items.length) : 1;
  const filtered = isFiltered(filters);

  return (
    <div className="space-y-6">
      <PageHeader title={t("instAudit.title")} description={t("instAudit.description", { institution: inst.ctx.institution })} />

      {data?.scope_filtered_out ? (
        <div className="flex items-start gap-3 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm" role="alert" data-testid="scope-filtered-out">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
          <div className="text-muted-foreground">
            <span className="font-medium text-foreground">{t("instAudit.withheld", { count: data.scope_filtered_out })}</span> {t("instAudit.withheldBody")}
          </div>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-1">
        {QUICK_FILTERS.map((f) => (
          <Button key={f.id} size="sm" variant={f.event_type === filters.event_type ? "secondary" : "ghost"} onClick={() => go({ event_type: f.event_type, page: 1 })}>
            {t(`instAudit.quick.${f.id}`)}
          </Button>
        ))}
      </div>

      <form className="flex flex-wrap items-end gap-3 rounded-xl border bg-card p-3 shadow-card" onSubmit={onFilter}>
        <label className="flex min-w-[200px] flex-1 flex-col gap-1 text-xs text-muted-foreground">
          {t("instAudit.target")}
          <Input value={draft.target} onChange={(e) => setDraft({ ...draft, target: e.target.value })} placeholder={t("instAudit.targetPlaceholder")} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted-foreground">
          {t("instAudit.severity")}
          <select
            value={draft.severity}
            onChange={(e) => setDraft({ ...draft, severity: e.target.value })}
            className="h-9 rounded-md border border-input bg-background px-2 text-sm text-foreground shadow-sm"
          >
            <option value="">{t("instAudit.any")}</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>
                {s.toLowerCase()}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted-foreground">
          {t("instAudit.from")}
          <Input type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} className="w-[150px]" />
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted-foreground">
          {t("instAudit.to")}
          <Input type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} className="w-[150px]" />
        </label>
        <div className="flex gap-2">
          <Button type="submit" variant="secondary">
            {t("instAudit.filter")}
          </Button>
          {filtered ? (
            <Button type="button" variant="ghost" onClick={reset}>
              {t("instAudit.reset")}
            </Button>
          ) : null}
        </div>
      </form>

      {q.isPending ? (
        <Loading />
      ) : q.isError || !data ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <div className="overflow-x-auto rounded-xl border bg-card shadow-card">
            <div className="min-w-[820px] divide-y">
              <div className={`${GRID} bg-muted/30 px-4 py-2 text-xs font-medium text-muted-foreground`}>
                <div>{t("instAudit.when")}</div>
                <div>{t("instAudit.actor")}</div>
                <div>{t("instAudit.actionCol")}</div>
                <div>{t("instAudit.target")}</div>
                <div>{t("instAudit.app")}</div>
                <div className="text-right">{t("instAudit.severity")}</div>
                <div />
              </div>
              {data.items.map((e) => (
                <EventRow key={e.id} e={e} />
              ))}
              {data.items.length === 0 ? <p className="px-4 py-8 text-center text-sm text-muted-foreground">{filtered ? t("instAudit.noMatch") : t("instAudit.empty")}</p> : null}
            </div>
          </div>
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">{t("instAudit.pageInfo", { total: data.total.toLocaleString(), page: filters.page, pages })}</p>
            <div className="flex gap-2">
              {filters.page > 1 ? (
                <Button variant="outline" size="sm" onClick={() => go({ page: filters.page - 1 })}>
                  ← {t("common.previous")}
                </Button>
              ) : null}
              {filters.page < pages ? (
                <Button variant="outline" size="sm" onClick={() => go({ page: filters.page + 1 })}>
                  {t("common.next")} →
                </Button>
              ) : null}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="view-members">
      <Audit />
    </CapabilityGate>
  );
}
