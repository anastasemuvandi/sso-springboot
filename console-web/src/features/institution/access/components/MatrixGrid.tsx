/**
 * The employee × application access matrix (virtualised).
 *
 * Every rule about what a click does lives in ../state/matrixState.ts (pure,
 * tested); this component renders from it and talks to the wrapper. Rows are
 * EMPLOYEES from the IPPIS mirror, so people with no SSO account appear too —
 * granting them an app creates the account server-side. Paging, search and
 * filters are server-side; the pending set is keyed by employee number +
 * client, so it survives moving between pages and searches. The wrapper
 * re-checks every item: this screen is not the boundary.
 */
import { useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getCoreRowModel, useReactTable, type ColumnDef } from "@tanstack/react-table";
import { useVirtualizer, type Rect, type Virtualizer } from "@tanstack/react-virtual";
import { AlertTriangle, Eye, Loader2, Mail, MinusCircle, PlusCircle, RotateCw } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ApiError, errorMessage } from "@/api/errors";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { useInstitution } from "../../context";
import { bulkAccess, matrixQuery, resendSetupEmail, revealNid } from "../api";
import {
  applyResults,
  bulkItems,
  cellView,
  columnSelection,
  counter,
  createdAccountsNote,
  displayName,
  initialState,
  matrixReducer,
  pendingCount,
  revokeSummary,
  rowSelection,
  setupEmailView,
  switchMode,
  type DisabledReason,
  type MatrixColumn,
  type MatrixRow,
  type Mode,
  type Tri,
} from "../state/matrixState";
import type { AccessFilter, GenderFilter } from "../types";

export const PAGE_SIZE = 50;
const ROW_HEIGHT = 44;
const VIEWPORT = { width: 1200, height: 640 };

type Banner = { kind: "ok" | "warn" | "error"; text: string; reload?: boolean };

/**
 * The scroll element's size; falls back to a fixed viewport when the element
 * reports 0×0 (jsdom, or before layout) so rows still render.
 */
function observeRect(instance: Virtualizer<HTMLDivElement, Element>, cb: (rect: Rect) => void) {
  const el = instance.scrollElement;
  if (!el) return;
  const report = () => {
    const r = el.getBoundingClientRect();
    cb({ width: r.width || VIEWPORT.width, height: r.height || VIEWPORT.height });
  };
  report();
  if (typeof ResizeObserver === "undefined") return;
  const ro = new ResizeObserver(report);
  ro.observe(el);
  return () => ro.disconnect();
}

/**
 * Render a header/cell definition by CALLING it, not mounting it as a
 * component: the definitions are re-created when the pending set changes, and
 * mounting a new function identity would remount every cell (losing focus and
 * detaching inputs mid-interaction).
 */
function render<C>(def: unknown, ctx: C): ReactNode {
  return typeof def === "function" ? (def as (c: C) => ReactNode)(ctx) : (def as ReactNode);
}

function TriCheckbox({ tri, onChange, label, title }: { tri: Tri; onChange: (on: boolean) => void; label: string; title?: string }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = tri === "some";
  }, [tri]);
  return (
    <span title={tri === "disabled" ? title : undefined}>
      <input
        ref={ref}
        type="checkbox"
        aria-label={label}
        checked={tri === "all"}
        disabled={tri === "disabled"}
        onChange={() => onChange(tri !== "all")}
        className="h-4 w-4 rounded border-input accent-primary disabled:opacity-40"
      />
    </span>
  );
}

export function MatrixGrid({ clientIds, canManage, canManageMembers }: { clientIds: string[]; canManage: boolean; canManageMembers: boolean }) {
  const { t } = useTranslation();
  const { scope } = useInstitution();
  const [state, dispatch] = useReducer(matrixReducer, undefined, () => initialState("grant"));
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<AccessFilter>("");
  const [filterClient, setFilterClient] = useState("");
  const [gender, setGender] = useState<GenderFilter>("");
  const [banner, setBanner] = useState<Banner | null>(null);
  const [applying, setApplying] = useState(false);
  const [modeAsk, setModeAsk] = useState<Mode | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const switchAfterApply = useRef<Mode | null>(null);
  const [nids, setNids] = useState<Record<string, string | null | "loading">>({});
  const [resending, setResending] = useState<string | null>(null);

  // Debounced search: one request per pause, not per keystroke.
  useEffect(() => {
    const h = setTimeout(() => {
      setQ(search.trim());
      setPage(1);
    }, 300);
    return () => clearTimeout(h);
  }, [search]);

  const query = useQuery({
    ...matrixQuery(scope, { clients: clientIds, page, page_size: PAGE_SIZE, q, access: filter, access_client: filterClient, gender }),
    placeholderData: keepPreviousData,
  });
  const data = query.data;

  // A fresh page is the truth: overrides describe the old one.
  useEffect(() => {
    if (query.dataUpdatedAt) dispatch({ type: "refreshed" });
  }, [query.dataUpdatedAt]);

  const columns: MatrixColumn[] = useMemo(() => data?.columns ?? [], [data]);
  const rows: MatrixRow[] = useMemo(() => data?.items ?? [], [data]);
  const pending = pendingCount(state);
  const pages = data ? Math.max(1, Math.ceil(data.total / data.page_size)) : 1;
  const grant = state.mode === "grant";
  const c = counter(state);
  const counterText = t(c.key, { count: c.count });

  const reasonText = (r?: DisabledReason) => (r?.text ? r.text : r?.key ? t(`instAccess.cell.${r.key}`) : undefined);
  const modeName = (m: Mode) => (m === "grant" ? t("instAccess.matrix.grantMode") : t("instAccess.matrix.revokeMode"));

  const requestMode = (to: Mode) => {
    const res = switchMode(state, to);
    if (res.kind === "switched") dispatch({ type: "setMode", mode: to });
    else setModeAsk(res.to);
  };

  const apply = async (): Promise<boolean> => {
    const items = bulkItems(state);
    if (items.length === 0) return false;
    setApplying(true);
    setBanner(null);
    const action = state.mode;
    try {
      const report = await bulkAccess(scope, action, items);
      const out = applyResults(state, action, report.results);
      dispatch({ type: "applied", action, results: report.results });
      const verb = action === "grant" ? t("instAccess.matrix.verbGranted") : t("instAccess.matrix.verbRevoked");
      const note = createdAccountsNote(out);
      const created =
        note.kind === "none"
          ? ""
          : note.kind === "emailed"
            ? ` ${t("instAccess.matrix.createdEmailed", { count: note.created })}`
            : ` ${t(note.all ? "instAccess.matrix.createdNotSentAll" : "instAccess.matrix.createdNotSentSome", { count: note.created, notSent: note.notSent })}`;
      // New accounts change row shape (account, password status, resend): refetch.
      if (out.accountsCreated > 0) void query.refetch();
      if (out.rejected + out.failed === 0) {
        setBanner({ kind: out.emailsNotSent > 0 ? "warn" : "ok", text: `${t("instAccess.matrix.applied", { count: out.applied, verb })}${created}` });
        return true;
      }
      setBanner({
        kind: "warn",
        reload: out.stateChanged,
        text:
          t("instAccess.matrix.appliedPartial", {
            applied: out.applied,
            verb,
            notApplied: out.rejected + out.failed,
            why: out.stateChanged ? t("instAccess.matrix.whyStateChanged") : t("instAccess.matrix.whyMarked"),
          }) + created,
      });
      return false;
    } catch (e) {
      // A whole-request refusal (403 scope, 400): nothing changed, all stays pending.
      setBanner({ kind: "error", text: errorMessage(e) });
      return false;
    } finally {
      setApplying(false);
    }
  };

  const onApplyClick = () => {
    if (state.mode === "revoke") setConfirmRevoke(true);
    else void apply();
  };

  const onReveal = async (n: string) => {
    setNids((x) => ({ ...x, [n]: "loading" }));
    try {
      const body = await revealNid(scope, n);
      setNids((x) => ({ ...x, [n]: body.nid }));
    } catch (e) {
      setNids(({ [n]: _drop, ...rest }) => rest);
      setBanner({ kind: "error", text: errorMessage(e) });
    }
  };

  const onResend = async (row: MatrixRow) => {
    setResending(row.employee_number);
    setBanner(null);
    try {
      const body = await resendSetupEmail(scope, row.employee_number);
      setBanner({ kind: body.status === "sent" ? "ok" : "warn", text: `${displayName(row)}: ${body.message}` });
      void query.refetch();
    } catch (e) {
      const text = e instanceof ApiError && e.status === 409 ? t("instAccess.matrix.resendRefused", { name: displayName(row) }) : `${displayName(row)}: ${errorMessage(e)}`;
      setBanner({ kind: "error", text });
    } finally {
      setResending(null);
    }
  };

  // ---- table definition (TanStack Table; rows virtualised below) ----
  const tableColumns = useMemo<ColumnDef<MatrixRow>[]>(() => {
    const fixed: ColumnDef<MatrixRow>[] = [
      {
        id: "select",
        header: () => null,
        cell: ({ row }) => (
          <TriCheckbox
            tri={rowSelection(state, row.original, columns, canManage)}
            onChange={(on) => dispatch({ type: "setRow", row: row.original, columns, on, canManage })}
            label={t("instAccess.matrix.selectRow", { name: displayName(row.original) })}
            title={t("instAccess.cell.disabledHeader")}
          />
        ),
      },
      {
        id: "nid",
        header: () => t("instAccess.matrix.colNid"),
        cell: ({ row: { original: r } }) => {
          const revealed = nids[r.employee_number];
          if (!r.has_nid) return <span className="text-muted-foreground">—</span>;
          if (revealed && revealed !== "loading") return <span data-testid={`nid-${r.employee_number}`}>{revealed}</span>;
          return (
            <button
              type="button"
              onClick={() => void onReveal(r.employee_number)}
              disabled={revealed === "loading"}
              className="inline-flex items-center gap-1 hover:underline"
              title={t("instAccess.matrix.revealNid")}
              aria-label={`${t("instAccess.matrix.revealNid")}: ${displayName(r)}`}
            >
              {r.nid_masked}
              <Eye className="h-3 w-3 text-muted-foreground" />
            </button>
          );
        },
      },
      { id: "first", header: () => t("instAccess.matrix.colFirst"), cell: ({ row: { original: r } }) => <span title={r.employee_number}>{r.first_name || "—"}</span> },
      {
        id: "last",
        header: () => t("instAccess.matrix.colLast"),
        cell: ({ row: { original: r } }) => {
          const email = setupEmailView(r);
          return (
            <span className="flex flex-wrap items-center gap-1.5">
              {r.last_name || "—"}
              {r.account === "none" ? (
                <Badge variant="outline" className="px-1 py-0 text-[10px]" title={t("instAccess.matrix.noAccountHint")}>
                  {t("instAccess.matrix.noAccount")}
                </Badge>
              ) : null}
              {r.locked_reason ? (
                <Badge variant="outline" className="px-1 py-0 text-[10px] text-amber-700" title={r.locked_reason}>
                  {t("instAccess.matrix.unresolved")}
                </Badge>
              ) : null}
              {r.status === "terminated" ? (
                <Badge variant="outline" className="px-1 py-0 text-[10px] opacity-70" title={t("instAccess.matrix.leftHint")}>
                  {t("instAccess.matrix.left")}
                </Badge>
              ) : null}
              {email ? (
                <>
                  <Badge variant="outline" className={cn("px-1 py-0 text-[10px]", email.tone === "warn" && "text-amber-700")} title={t(`instAccess.setupEmail.${email.kind}Hint`)}>
                    {t(`instAccess.setupEmail.${email.kind}`)}
                  </Badge>
                  {email.resendable && canManageMembers ? (
                    <Button size="sm" variant="outline" className="h-6 px-2 text-[11px]" disabled={resending !== null} onClick={() => void onResend(r)} title={t("instAccess.matrix.resendHint")}>
                      {resending === r.employee_number ? <Loader2 className="animate-spin" /> : <Mail />}
                      {t("instAccess.matrix.resend")}
                    </Button>
                  ) : null}
                </>
              ) : null}
              {r.account_enabled === false ? (
                <Badge variant="outline" className="px-1 py-0 text-[10px] opacity-70">
                  {t("instAccess.matrix.accountDisabled")}
                </Badge>
              ) : null}
            </span>
          );
        },
      },
      { id: "email", header: () => t("instAccess.matrix.colEmail"), cell: ({ row: { original: r } }) => (r.email ? <span className="break-all">{r.email}</span> : <span className="text-muted-foreground">—</span>) },
      {
        id: "gender",
        header: () => t("instAccess.matrix.colGender"),
        cell: ({ row: { original: r } }) =>
          r.gender === "M" ? t("instAccess.matrix.male") : r.gender === "F" ? t("instAccess.matrix.female") : <span className="text-muted-foreground">—</span>,
      },
    ];
    const apps: ColumnDef<MatrixRow>[] = columns.map((col) => ({
      id: `app:${col.client_id}`,
      header: () => (
        <div className="flex flex-col items-center gap-1 py-1 normal-case tracking-normal">
          <span className="text-xs font-medium text-foreground" title={col.client_id}>
            {col.name || col.client_id}
          </span>
          <span className="flex items-center gap-1">
            <Badge variant="outline" className="px-1 py-0 text-[10px]">
              {col.type === "A" ? t("instAccess.matrix.own") : t("instAccess.matrix.shared")}
            </Badge>
            {!col.editable ? (
              <Badge variant="outline" className="px-1 py-0 text-[10px] opacity-70" title={col.reason ?? undefined}>
                {t("instAccess.matrix.viewOnly")}
              </Badge>
            ) : null}
          </span>
          <TriCheckbox
            tri={columnSelection(state, rows, col, canManage)}
            onChange={(on) => dispatch({ type: "setColumn", rows, column: col, on, canManage })}
            label={t("instAccess.matrix.selectColumn", { client: col.client_id })}
            title={col.reason ?? t("instAccess.cell.disabledHeader")}
          />
          <span className="text-[10px] text-muted-foreground">{t("instAccess.matrix.withAccess", { count: data?.counts[col.client_id] ?? 0 })}</span>
        </div>
      ),
      cell: ({ row: { original: r } }) => {
        const v = cellView(state, r, col, canManage);
        const why = v.error ?? (v.enabled ? undefined : reasonText(v.disabledReason));
        return (
          <span className="inline-flex items-center gap-1" title={why}>
            <input
              type="checkbox"
              aria-label={t("instAccess.matrix.cellLabel", { client: col.client_id, name: displayName(r) })}
              checked={v.checked}
              disabled={!v.enabled}
              data-pending={v.pending || undefined}
              onChange={() => dispatch({ type: "toggle", row: r, column: col, canManage })}
              className={cn("h-4 w-4 rounded border-input disabled:opacity-40", grant ? "accent-emerald-600" : "accent-destructive")}
            />
            {v.error ? <AlertTriangle className="h-3.5 w-3.5 text-amber-600" aria-label={v.error} role="img" /> : null}
          </span>
        );
      },
    }));
    return [...fixed, ...apps];
    // eslint-disable-next-line react-hooks/exhaustive-deps -- closures read the latest state/nids by design
  }, [state, columns, rows, canManage, canManageMembers, nids, resending, data, t, grant]);

  const table = useReactTable({ data: rows, columns: tableColumns, getCoreRowModel: getCoreRowModel(), getRowId: (r) => r.employee_number });
  const tableRows = table.getRowModel().rows;

  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: tableRows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 10,
    initialRect: VIEWPORT,
    observeElementRect: observeRect,
  });
  const items = virtualizer.getVirtualItems();
  const padTop = items.length ? (items[0]?.start ?? 0) : 0;
  const padBottom = items.length ? virtualizer.getTotalSize() - (items[items.length - 1]?.end ?? 0) : 0;
  const colCount = tableColumns.length;

  const select = (label: string, value: string, onChange: (v: string) => void, children: ReactNode) => (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} className="h-9 rounded-md border border-input bg-background px-2 text-sm">
      {children}
    </select>
  );

  return (
    <div className="space-y-4">
      {/* ---- mode: the most consequential control on the page ---- */}
      <div className={cn("flex flex-wrap items-center justify-between gap-3 rounded-xl border-2 px-4 py-3", grant ? "border-emerald-600/60 bg-emerald-600/5" : "border-destructive/60 bg-destructive/5")}>
        <div className="flex items-center gap-3">
          <div role="radiogroup" aria-label={t("instAccess.matrix.modeLabel")} className="inline-flex rounded-lg border bg-background p-0.5">
            {(["grant", "revoke"] as Mode[]).map((m) => (
              <button
                key={m}
                type="button"
                role="radio"
                aria-checked={state.mode === m}
                disabled={!canManage}
                onClick={() => requestMode(m)}
                className={cn(
                  "flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                  state.mode === m ? (m === "grant" ? "bg-emerald-600 text-white" : "bg-destructive text-destructive-foreground") : "text-muted-foreground hover:text-foreground",
                )}
              >
                {m === "grant" ? <PlusCircle className="h-4 w-4" /> : <MinusCircle className="h-4 w-4" />}
                {modeName(m)}
              </button>
            ))}
          </div>
          <p className={cn("text-sm font-semibold", grant ? "text-emerald-700 dark:text-emerald-400" : "text-destructive")}>
            {grant ? t("instAccess.matrix.grantHeadline") : t("instAccess.matrix.revokeHeadline")}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-sm tabular-nums" aria-live="polite" data-testid="pending-counter">
            {counterText}
          </span>
          <Button variant="ghost" size="sm" disabled={pending === 0 || applying} onClick={() => dispatch({ type: "discard" })}>
            {t("instAccess.matrix.discard")}
          </Button>
          <Button size="sm" variant={grant ? "default" : "destructive"} disabled={pending === 0 || applying} onClick={onApplyClick}>
            {applying ? <Loader2 className="animate-spin" /> : null}
            {t("instAccess.matrix.apply")}
          </Button>
        </div>
      </div>

      {!canManage ? <p className="text-xs text-muted-foreground">{t("instAccess.matrix.readOnlyNote")}</p> : null}

      {banner ? (
        <div
          role="status"
          data-testid="matrix-banner"
          className={cn(
            "flex items-start justify-between gap-3 rounded-lg border px-4 py-2.5 text-sm",
            banner.kind === "ok" && "border-emerald-600/40 bg-emerald-600/5",
            banner.kind === "warn" && "border-amber-500/50 bg-amber-500/10",
            banner.kind === "error" && "border-destructive/50 bg-destructive/5 text-destructive",
          )}
        >
          <span>{banner.text}</span>
          {banner.kind === "warn" && banner.reload ? (
            <Button size="sm" variant="outline" onClick={() => void query.refetch()}>
              <RotateCw /> {t("instAccess.matrix.reload")}
            </Button>
          ) : null}
        </div>
      ) : null}

      {/* ---- search / filter ---- */}
      <div className="flex flex-wrap items-center gap-2">
        <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("instAccess.matrix.search")} aria-label={t("instAccess.matrix.search")} className="max-w-xs" />
        {select(
          t("instAccess.matrix.filterAccess"),
          filter,
          (v) => {
            setFilter(v as AccessFilter);
            setPage(1);
          },
          <>
            <option value="">{t("instAccess.matrix.everyone")}</option>
            <option value="has">{t("instAccess.matrix.hasAccess")}</option>
            <option value="none">{t("instAccess.matrix.noAccess")}</option>
          </>,
        )}
        {filter
          ? select(
              t("instAccess.matrix.filterApplies"),
              filterClient,
              (v) => {
                setFilterClient(v);
                setPage(1);
              },
              <>
                <option value="">{t("instAccess.matrix.anyApp")}</option>
                {columns.map((col) => (
                  <option key={col.client_id} value={col.client_id}>
                    {t("instAccess.matrix.toApp", { name: col.name || col.client_id })}
                  </option>
                ))}
              </>,
            )
          : null}
        {select(
          t("instAccess.matrix.filterGender"),
          gender,
          (v) => {
            setGender(v as GenderFilter);
            setPage(1);
          },
          <>
            <option value="">{t("instAccess.matrix.anyGender")}</option>
            <option value="M">{t("instAccess.matrix.male")}</option>
            <option value="F">{t("instAccess.matrix.female")}</option>
            <option value="unknown">{t("instAccess.matrix.notRecorded")}</option>
          </>,
        )}
        {query.isFetching ? <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" /> : null}
        {data ? <span className="ml-auto text-xs text-muted-foreground">{t("instAccess.matrix.ofEmployees", { total: data.total, all: data.total_employees })}</span> : null}
      </div>
      <p className="text-xs text-muted-foreground">{t("instAccess.matrix.revealedAudited")}</p>

      {query.isError ? (
        <div role="alert" className="rounded-lg border border-destructive/50 bg-destructive/5 px-4 py-3 text-sm text-destructive">
          {errorMessage(query.error)}
        </div>
      ) : null}

      {/* ---- the matrix (virtualised rows) ---- */}
      {data ? (
        <div ref={scrollRef} className="max-h-[70vh] overflow-auto rounded-xl border bg-card shadow-card" data-testid="matrix-scroll">
          <table className="w-full caption-bottom text-sm">
            <thead className="sticky top-0 z-10 bg-card shadow-[0_1px_0_hsl(var(--border))]">
              {table.getHeaderGroups().map((hg) => (
                <tr key={hg.id}>
                  {hg.headers.map((h) => (
                    <th
                      key={h.id}
                      className={cn("h-10 px-3 align-bottom text-xs font-medium uppercase tracking-wider text-muted-foreground", h.column.id.startsWith("app:") ? "min-w-[120px] text-center" : "text-left", h.column.id === "select" && "w-10")}
                    >
                      {render(h.column.columnDef.header, h.getContext())}
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody>
              {padTop > 0 ? (
                <tr aria-hidden>
                  <td colSpan={colCount} style={{ height: padTop }} />
                </tr>
              ) : null}
              {items.map((vi) => {
                const row = tableRows[vi.index];
                if (!row) return null;
                const r = row.original;
                return (
                  <tr key={row.id} data-index={vi.index} style={{ height: ROW_HEIGHT }} className={cn("border-b", (r.locked_reason || r.status === "terminated") && "opacity-70")} data-testid={`row-${r.employee_number}`}>
                    {row.getVisibleCells().map((cell) => {
                      const app = cell.column.id.startsWith("app:") ? cell.column.id.slice(4) : null;
                      const pendingCell = app ? cellView(state, r, columns.find((x) => x.client_id === app)!, canManage).pending : false;
                      return (
                        <td key={cell.id} className={cn("px-3 py-1.5 align-middle", app && "text-center", pendingCell && (grant ? "bg-emerald-600/10" : "bg-destructive/10"), (cell.column.id === "nid") && "font-mono text-xs")}>
                          {render(cell.column.columnDef.cell, cell.getContext())}
                        </td>
                      );
                    })}
                  </tr>
                );
              })}
              {padBottom > 0 ? (
                <tr aria-hidden>
                  <td colSpan={colCount} style={{ height: padBottom }} />
                </tr>
              ) : null}
              {rows.length === 0 ? (
                <tr>
                  <td colSpan={colCount} className="py-8 text-center text-muted-foreground">
                    {t("instAccess.matrix.nobody")}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      ) : query.isPending ? (
        <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          {t("common.loading")}
        </div>
      ) : null}

      {data && pages > 1 ? (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button variant="outline" size="sm" disabled={page <= 1 || query.isFetching} onClick={() => setPage((p) => p - 1)}>
            {t("common.previous")}
          </Button>
          <span className="tabular-nums text-muted-foreground">{t("instAccess.matrix.pageOf", { page: data.page, pages })}</span>
          <Button variant="outline" size="sm" disabled={page >= pages || query.isFetching} onClick={() => setPage((p) => p + 1)}>
            {t("common.next")}
          </Button>
        </div>
      ) : null}

      {/* ---- switching mode with changes pending: a decision, never a silent drop ---- */}
      <Dialog open={modeAsk !== null} onOpenChange={(o) => !o && setModeAsk(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("instAccess.matrix.pendingDialogTitle", { counter: counterText })}</DialogTitle>
            <DialogDescription>{t("instAccess.matrix.pendingDialogBody", { mode: modeAsk ? modeName(modeAsk) : "" })}</DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2">
            <Button variant="ghost" onClick={() => setModeAsk(null)}>
              {t("instAccess.matrix.stayIn", { mode: modeName(state.mode) })}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                const to = modeAsk!;
                setModeAsk(null);
                dispatch({ type: "discardAndSwitch", mode: to });
              }}
            >
              {t("instAccess.matrix.discardSwitch")}
            </Button>
            <Button
              variant={grant ? "default" : "destructive"}
              disabled={applying}
              onClick={async () => {
                const to = modeAsk!;
                setModeAsk(null);
                // Revoking still needs its own confirmation.
                if (state.mode === "revoke") {
                  switchAfterApply.current = to;
                  setConfirmRevoke(true);
                } else if (await apply()) {
                  dispatch({ type: "setMode", mode: to });
                }
              }}
            >
              {t("instAccess.matrix.applyFirst")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ---- revoke confirmation: counts per client, names when ≤ 10 ---- */}
      <Dialog
        open={confirmRevoke}
        onOpenChange={(o) => {
          setConfirmRevoke(o);
          if (!o) switchAfterApply.current = null;
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="text-destructive">{t("instAccess.matrix.revokeConfirmTitle", { count: pending })}</DialogTitle>
            <DialogDescription>{t("instAccess.matrix.revokeConfirmBody")}</DialogDescription>
          </DialogHeader>
          <ul className="max-h-72 space-y-2 overflow-y-auto text-sm">
            {revokeSummary(state, columns).map((s) => (
              <li key={s.client_id} className="rounded-md border px-3 py-2">
                <div className="font-medium">
                  {s.name} <span className="font-normal text-muted-foreground">— {s.count}</span>
                </div>
                {s.names ? <div className="mt-0.5 text-xs text-muted-foreground">{s.names.join(", ")}</div> : null}
              </li>
            ))}
          </ul>
          <DialogFooter className="gap-2">
            <Button variant="ghost" onClick={() => setConfirmRevoke(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={applying}
              onClick={async () => {
                setConfirmRevoke(false);
                const to = switchAfterApply.current;
                switchAfterApply.current = null;
                if ((await apply()) && to) dispatch({ type: "setMode", mode: to });
              }}
            >
              {t("instAccess.matrix.revokeConfirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
