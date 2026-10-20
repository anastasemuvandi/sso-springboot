/**
 * /system-audit — the audit service's tamper-evident, hash-chained trail
 * (proxied by the wrapper; the audit API key never leaves the server).
 * Filters live in the URL; integrity verification, CSV/PDF export and the
 * retention policies sit alongside. Each read fails on its own.
 */
import { Fragment, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { ChevronRight, Download } from "lucide-react";
import { useTranslation } from "react-i18next";

import { downloadFile } from "@/api/client";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState, ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatWhen } from "@/lib/utils";
import { auditEventsQuery, retentionQuery } from "../api";
import { IntegrityBanner } from "../components/IntegrityBanner";
import { CATEGORIES, SEVERITIES, auditExportPath, filtersFromSearch, pageInfo, searchFromFilters, severityVariant } from "../state/auditFilters";
import type { AuditEventFilters, ExportFormat } from "../types";

const SELECT = "h-9 rounded-md border border-input bg-background px-2 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40";

export function Component() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const filters = filtersFromSearch(sp);
  const events = useQuery(auditEventsQuery(filters));
  const retention = useQuery(retentionQuery());
  const [open, setOpen] = useState<string | null>(null);
  const [exporting, setExporting] = useState<ExportFormat | null>(null);

  function apply(next: AuditEventFilters) {
    setSp(searchFromFilters(next));
  }

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const fd = new FormData(e.currentTarget);
    const get = (k: string) => String(fd.get(k) ?? "").trim() || undefined;
    apply({
      category: get("category"),
      severity: get("severity"),
      event_type: get("event_type"),
      user_id: get("user_id"),
      app_id: get("app_id"),
      from_date: get("from_date"),
      to_date: get("to_date"),
      limit: Number(get("limit") ?? 100),
      offset: 0,
    });
  }

  async function onExport(fmt: ExportFormat) {
    setExporting(fmt);
    try {
      await downloadFile(auditExportPath(fmt, filters), `audit_export.${fmt}`);
    } catch (e) {
      notify.error(e, t("audit.export.failed"));
    } finally {
      setExporting(null);
    }
  }

  const items = events.data?.items ?? [];
  const limit = filters.limit ?? 100;
  const info = events.data ? pageInfo(events.data.total, filters.offset ?? 0, limit, items.length) : null;
  const formKey = sp.toString();

  return (
    <div className="space-y-6">
      <PageHeader title={t("audit.system.title")} description={t("audit.system.description")} />

      <IntegrityBanner />

      <Card>
        <CardHeader>
          <CardTitle>{t("audit.filters.title")}</CardTitle>
          <CardDescription>{t("audit.filters.description")}</CardDescription>
        </CardHeader>
        <CardContent>
          <form key={formKey} onSubmit={onSubmit} className="flex flex-wrap items-end gap-3" aria-label={t("audit.filters.title")}>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.category")}
              <select name="category" defaultValue={filters.category ?? ""} className={SELECT}>
                <option value="">{t("audit.filters.all")}</option>
                {CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.severity")}
              <select name="severity" defaultValue={filters.severity ?? ""} className={SELECT}>
                <option value="">{t("audit.filters.all")}</option>
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.eventType")}
              <Input name="event_type" defaultValue={filters.event_type ?? ""} placeholder="auth.login.success" className="w-[190px]" />
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.userId")}
              <Input name="user_id" defaultValue={filters.user_id ?? ""} className="w-[170px]" />
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.appId")}
              <Input name="app_id" defaultValue={filters.app_id ?? ""} className="w-[150px]" />
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.from")}
              <Input type="date" name="from_date" defaultValue={filters.from_date ?? ""} className="w-[150px]" />
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.to")}
              <Input type="date" name="to_date" defaultValue={filters.to_date ?? ""} className="w-[150px]" />
            </label>
            <label className="flex flex-col gap-1 text-xs font-medium text-muted-foreground">
              {t("audit.filters.max")}
              <Input type="number" name="limit" min={1} max={1000} defaultValue={String(limit)} className="w-[90px]" />
            </label>
            <Button type="submit" variant="secondary" size="sm">
              {t("audit.filters.apply")}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setSp(new URLSearchParams())}>
              {t("audit.filters.reset")}
            </Button>
            <div className="ml-auto flex gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => void onExport("csv")} disabled={exporting !== null} data-testid="export-csv">
                <Download />
                {t("audit.export.csv")}
              </Button>
              <Button type="button" variant="outline" size="sm" onClick={() => void onExport("pdf")} disabled={exporting !== null} data-testid="export-pdf">
                <Download />
                {t("audit.export.pdf")}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
          <CardTitle>
            {t("audit.events.title")}
            {info ? <span className="ml-2 text-sm font-normal text-muted-foreground">{t("audit.events.range", { from: info.from, to: info.to, total: info.total.toLocaleString() })}</span> : null}
          </CardTitle>
          {info ? (
            <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={!info.hasPrev} onClick={() => apply({ ...filters, offset: info.prevOffset })}>
                {t("common.previous")}
              </Button>
              <Button size="sm" variant="outline" disabled={!info.hasNext} onClick={() => apply({ ...filters, offset: info.nextOffset })}>
                {t("common.next")}
              </Button>
            </div>
          ) : null}
        </CardHeader>
        <CardContent>
          {events.isPending ? (
            <Loading />
          ) : events.isError ? (
            <ErrorState error={events.error} onRetry={() => void events.refetch()} />
          ) : items.length === 0 ? (
            <EmptyState>{t("audit.events.none")}</EmptyState>
          ) : (
            <div className="rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-6" />
                    <TableHead>{t("common.time")}</TableHead>
                    <TableHead>{t("audit.events.type")}</TableHead>
                    <TableHead>{t("audit.events.actor")}</TableHead>
                    <TableHead>{t("audit.events.app")}</TableHead>
                    <TableHead>{t("audit.filters.severity")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((e) => {
                    const expanded = open === e.id;
                    return (
                      <Fragment key={e.id}>
                        <TableRow className="cursor-pointer" onClick={() => setOpen(expanded ? null : e.id)} aria-expanded={expanded}>
                          <TableCell>
                            <ChevronRight className={`h-4 w-4 text-muted-foreground transition-transform ${expanded ? "rotate-90" : ""}`} />
                          </TableCell>
                          <TableCell className="whitespace-nowrap font-mono text-xs">{formatWhen(e.created_at)}</TableCell>
                          <TableCell className="font-mono text-xs">{e.event_type}</TableCell>
                          <TableCell className="font-mono text-xs">{e.performed_by ?? e.user_id ?? "—"}</TableCell>
                          <TableCell className="font-mono text-xs">{e.app_id ?? "—"}</TableCell>
                          <TableCell>
                            <Badge variant={severityVariant(e.severity)}>{e.severity}</Badge>
                          </TableCell>
                        </TableRow>
                        {expanded ? (
                          <TableRow className="hover:bg-transparent">
                            <TableCell colSpan={6} className="bg-muted/30">
                              <pre className="max-h-96 overflow-auto rounded-md bg-background p-3 font-mono text-xs">{JSON.stringify(e, null, 2)}</pre>
                            </TableCell>
                          </TableRow>
                        ) : null}
                      </Fragment>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("audit.retention.title")}</CardTitle>
          <CardDescription>{t("audit.retention.description")}</CardDescription>
        </CardHeader>
        <CardContent>
          {retention.isPending ? (
            <Loading />
          ) : retention.isError ? (
            <ErrorState error={retention.error} />
          ) : retention.data.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("audit.retention.none")}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("audit.filters.category")}</TableHead>
                  <TableHead>{t("audit.retention.days")}</TableHead>
                  <TableHead>{t("audit.retention.legalHold")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {retention.data.map((p) => (
                  <TableRow key={p.id}>
                    <TableCell className="font-mono text-xs">{p.category ?? t("audit.retention.default")}</TableCell>
                    <TableCell>{p.retention_days}</TableCell>
                    <TableCell>{p.legal_hold ? <Badge variant="destructive">{t("audit.retention.held")}</Badge> : <span className="text-muted-foreground">—</span>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
