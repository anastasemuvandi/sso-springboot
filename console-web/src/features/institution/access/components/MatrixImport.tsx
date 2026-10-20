/**
 * Import an edited access-matrix export: upload → preview → confirm → results.
 *
 * The preview writes nothing; the wrapper keeps the grants it found as a plan
 * (30 minutes) and "Confirm" applies THAT plan — not the file — once. Imports
 * only grant: a cleared YES is shown as "Cleared, not revoked". Every rule is
 * the wrapper's; this is presentation.
 */
import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { AlertTriangle, CheckCircle2, Download, FileSpreadsheet, Loader2, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatWhen } from "@/lib/utils";
import { useInstitution } from "../../context";
import { applyImport, previewImport } from "../api";
import { filterItems, grantsToApply, OUTCOME_ORDER, outcomeTone, reportCsv, ATTENTION, type ImportFilter } from "../state/importState";
import type { ImportPreview, ImportReport } from "../types";

type Phase =
  | { kind: "upload"; error?: string }
  | { kind: "previewing" }
  | { kind: "preview"; preview: ImportPreview; error?: string }
  | { kind: "applying"; preview: ImportPreview }
  | { kind: "done"; preview: ImportPreview; report: ImportReport };

function ErrorLine({ text }: { text: string }) {
  return (
    <div role="alert" className="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
      {text}
    </div>
  );
}

export function MatrixImport({ matrixHref }: { matrixHref: string }) {
  const { t } = useTranslation();
  const { scope } = useInstitution();
  const [phase, setPhase] = useState<Phase>({ kind: "upload" });
  const [filter, setFilter] = useState<ImportFilter>("attention");
  const [file, setFile] = useState<File | null>(null);
  const [approvedBy, setApprovedBy] = useState("");

  async function onPreview(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const approver = approvedBy.trim();
    if (!file || file.size === 0) return setPhase({ kind: "upload", error: t("instAccess.import.chooseFile") });
    if (approver.length < 2) return setPhase({ kind: "upload", error: t("instAccess.import.approvedByShort") });
    setPhase({ kind: "previewing" });
    try {
      const preview = await previewImport(scope, file, approver);
      setFilter("attention");
      setPhase({ kind: "preview", preview });
    } catch (err) {
      setPhase({ kind: "upload", error: errorMessage(err) });
    }
  }

  async function onApply(preview: ImportPreview) {
    if (!preview.import_id) return;
    setPhase({ kind: "applying", preview });
    try {
      setPhase({ kind: "done", preview, report: await applyImport(scope, preview.import_id) });
    } catch (err) {
      setPhase({ kind: "preview", preview, error: errorMessage(err) });
    }
  }

  function downloadReport(report: ImportReport) {
    const url = URL.createObjectURL(new Blob([reportCsv(report)], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `access-import-${report.import_id.slice(0, 8)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  // ---- 1. upload ----
  if (phase.kind === "upload" || phase.kind === "previewing") {
    const busy = phase.kind === "previewing";
    return (
      <form onSubmit={(e) => void onPreview(e)} className="space-y-4 rounded-xl border bg-card p-5 shadow-card" noValidate>
        <ol className="list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
          <li>
            <Link to={matrixHref} className="underline">
              {t("instAccess.import.step1")}
            </Link>
          </li>
          <li>{t("instAccess.import.step2")}</li>
          <li>{t("instAccess.import.step3")}</li>
        </ol>
        {phase.kind === "upload" && phase.error ? <ErrorLine text={phase.error} /> : null}
        <label className="flex flex-col gap-1 text-sm font-medium">
          {t("instAccess.import.file")}
          <Input
            type="file"
            name="file"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            disabled={busy}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium">
          {t("instAccess.import.approvedBy")}
          <Input
            name="approved_by"
            maxLength={120}
            value={approvedBy}
            onChange={(e) => setApprovedBy(e.target.value)}
            placeholder={t("instAccess.import.approvedByPlaceholder")}
            disabled={busy}
          />
          <span className="text-xs font-normal text-muted-foreground">{t("instAccess.import.approvedByHint")}</span>
        </label>
        <div className="flex justify-end">
          <Button type="submit" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <Upload />}
            {busy ? t("instAccess.import.reading") : t("instAccess.import.preview")}
          </Button>
        </div>
      </form>
    );
  }

  const preview = phase.preview;
  const header = (
    <div className="flex flex-wrap items-start gap-3 rounded-xl border bg-card p-4 text-sm shadow-card">
      <FileSpreadsheet className="mt-0.5 h-5 w-5 text-muted-foreground" />
      <div className="space-y-0.5">
        <div className="font-medium">{preview.file_name ?? t("instAccess.import.uploaded")}</div>
        <div className="text-xs text-muted-foreground">
          {t("instAccess.import.exportedBy", { when: formatWhen(preview.exported_at), who: preview.exported_by, approver: preview.approved_by })}
        </div>
        <div className="flex flex-wrap gap-1 pt-1">
          {preview.clients.map((c) => (
            <Badge key={c} variant="outline" className="font-mono">
              {c}
            </Badge>
          ))}
        </div>
      </div>
    </div>
  );

  // ---- 3. done ----
  if (phase.kind === "done") {
    const { report } = phase;
    return (
      <div className="space-y-4" data-testid="import-done">
        {header}
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-sm">
          <div className="flex items-center gap-2">
            <CheckCircle2 className="h-4 w-4 text-emerald-600" />
            <span>
              {t("instAccess.import.doneSummary", {
                applied: report.summary.applied ?? 0,
                rejected: report.summary.rejected ? t("instAccess.import.doneRejected", { count: report.summary.rejected }) : "",
                failed: report.summary.failed ? t("instAccess.import.doneFailed", { count: report.summary.failed }) : "",
                id: report.import_id.slice(0, 8),
              })}
            </span>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => downloadReport(report)}>
              <Download /> {t("instAccess.import.downloadResults")}
            </Button>
            <Button asChild size="sm">
              <Link to={matrixHref}>{t("instAccess.import.backToMatrix")}</Link>
            </Button>
          </div>
        </div>
        <div className="overflow-x-auto rounded-xl border bg-card shadow-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("instAccess.import.colEmployee")}</TableHead>
                <TableHead>{t("instAccess.import.colApp")}</TableHead>
                <TableHead>{t("instAccess.import.colResult")}</TableHead>
                <TableHead>{t("instAccess.import.colDetail")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {report.results.map((r) => (
                <TableRow key={`${r.employee_number}:${r.client_id}`}>
                  <TableCell className="font-mono text-xs">{r.employee_number}</TableCell>
                  <TableCell className="font-mono text-xs">{r.client_id}</TableCell>
                  <TableCell>
                    <Badge variant={r.status === "applied" ? "default" : r.status === "failed" ? "destructive" : "outline"}>{r.status}</Badge>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {r.message ?? (r.account_created ? t("instAccess.import.accountCreated", { status: r.setup_email ?? "—" }) : "—")}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </div>
    );
  }

  // ---- 2. preview ----
  const applying = phase.kind === "applying";
  const toApply = grantsToApply(preview);
  const shown = filterItems(preview.items, filter);
  const needsAttention = preview.items.filter((i) => ATTENTION.includes(i.outcome)).length;

  return (
    <div className="space-y-4" data-testid="import-preview">
      {header}
      {phase.kind === "preview" && phase.error ? <ErrorLine text={phase.error} /> : null}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {OUTCOME_ORDER.map((o) => (
          <button
            key={o}
            type="button"
            onClick={() => setFilter(o)}
            className={`rounded-lg border px-3 py-2 text-left transition-colors ${filter === o ? "border-primary bg-accent" : "bg-card hover:bg-accent"}`}
            data-testid={`outcome-${o}`}
          >
            <div className="text-lg font-semibold tabular-nums">{preview.summary[o] ?? 0}</div>
            <div className="text-xs text-muted-foreground">{t(`instAccess.import.outcome.${o}`)}</div>
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-card px-4 py-3 shadow-card">
        <p className="text-sm">
          {toApply > 0
            ? t("instAccess.import.confirmGrants", {
                count: toApply,
                created: preview.summary.will_create_account ? t("instAccess.import.createdWhereNeeded", { count: preview.summary.will_create_account }) : "",
              })
            : t("instAccess.import.nothingToConfirm")}
          {preview.expires_at ? <span className="block text-xs text-muted-foreground">{t("instAccess.import.confirmBefore", { when: formatWhen(preview.expires_at) })}</span> : null}
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => {
              setFile(null);
              setPhase({ kind: "upload" });
            }}
            disabled={applying}>
            {t("instAccess.import.startOver")}
          </Button>
          <Button onClick={() => void onApply(preview)} disabled={!preview.import_id || toApply === 0 || applying}>
            {applying ? <Loader2 className="animate-spin" /> : null}
            {applying ? t("instAccess.import.applying") : t("instAccess.import.confirm", { count: toApply })}
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap gap-1">
        <Button size="sm" variant={filter === "attention" ? "secondary" : "ghost"} onClick={() => setFilter("attention")}>
          {t("instAccess.import.needsAttention", { count: needsAttention })}
        </Button>
        <Button size="sm" variant={filter === "all" ? "secondary" : "ghost"} onClick={() => setFilter("all")}>
          {t("instAccess.import.allListed", { count: preview.items.length })}
        </Button>
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card shadow-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-16">{t("instAccess.import.colRow")}</TableHead>
              <TableHead>{t("instAccess.import.colEmployee")}</TableHead>
              <TableHead>{t("instAccess.import.colApp")}</TableHead>
              <TableHead>{t("instAccess.import.colOutcome")}</TableHead>
              <TableHead>{t("instAccess.import.colWhy")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((i, n) => (
              <TableRow key={`${i.row}:${i.employee_number}:${i.client_id}:${n}`}>
                <TableCell className="font-mono text-xs text-muted-foreground">{i.row ?? "—"}</TableCell>
                <TableCell className="text-xs">
                  <span className="font-mono">{i.employee_number ?? "—"}</span>
                  {i.name ? <span className="block text-muted-foreground">{i.name}</span> : null}
                </TableCell>
                <TableCell className="font-mono text-xs">{i.client_id ?? "—"}</TableCell>
                <TableCell>
                  <Badge variant={outcomeTone(i.outcome)}>{t(`instAccess.import.outcome.${i.outcome}`)}</Badge>
                </TableCell>
                <TableCell className="max-w-[420px] text-xs text-muted-foreground">{i.message ?? i.reason ?? "—"}</TableCell>
              </TableRow>
            ))}
            {shown.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-sm text-muted-foreground">
                  {filter === "attention" ? t("instAccess.import.nothingAttention") : t("instAccess.import.nothingHere")}
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
