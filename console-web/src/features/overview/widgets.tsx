/**
 * Building blocks of both overview pages (platform `/dashboard` and the
 * institution console's `/institution`). Presentational only.
 */
import type { ComponentType, ReactNode } from "react";
import { Link } from "react-router-dom";
import { AlertTriangle, ArrowRight, CircleCheck } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn, formatCount, formatWhen } from "@/lib/utils";

type Icon = ComponentType<{ className?: string }>;

/** A count with a breakdown and a link. `value: null` = could not be read → "—", never 0. */
export function StatCard({
  icon: IconC,
  label,
  value,
  breakdown,
  href,
  cta,
  hint,
  tone = "default",
  testId,
}: {
  icon: Icon;
  label: string;
  value: number | null;
  breakdown?: Array<{ label: string; value: number | null; tone?: "warn" }>;
  href?: string;
  cta?: string;
  hint?: string;
  tone?: "default" | "warn";
  testId?: string;
}) {
  return (
    <div className={cn("flex flex-col justify-between rounded-xl border bg-card p-5 shadow-card", tone === "warn" && "border-amber-500/50")} data-testid={testId}>
      <div>
        <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
          <IconC className="h-3.5 w-3.5" />
          {label}
        </div>
        <div className="mt-2 text-2xl font-semibold tabular-nums text-foreground" data-testid={testId ? `${testId}-value` : undefined}>
          {formatCount(value)}
        </div>
        {breakdown && breakdown.length > 0 ? (
          <dl className="mt-2 space-y-0.5 text-xs">
            {breakdown.map((b) => (
              <div key={b.label} className="flex justify-between gap-3">
                <dt className="text-muted-foreground">{b.label}</dt>
                <dd className={cn("tabular-nums", b.tone === "warn" && (b.value ?? 0) > 0 && "font-medium text-amber-700 dark:text-amber-400")}>
                  {formatCount(b.value)}
                </dd>
              </div>
            ))}
          </dl>
        ) : null}
        {hint ? <p className="mt-2 text-xs text-muted-foreground">{hint}</p> : null}
      </div>
      {href && cta ? (
        <div className="mt-4">
          <Button asChild size="sm" variant="outline">
            <Link to={href}>{cta}</Link>
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export interface QuickAction {
  label: string;
  href: string;
  icon: Icon;
  description: string;
}

export function QuickActions({ actions }: { actions: QuickAction[] }) {
  const { t } = useTranslation();
  if (actions.length === 0) return null;
  return (
    <section>
      <h2 className="text-base font-semibold text-foreground">{t("overview.quickActions")}</h2>
      <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {actions.map(({ label, href, icon: IconC, description }) => (
          <Link
            key={href}
            to={href}
            className="group flex items-start gap-3 rounded-lg border bg-card px-4 py-3 shadow-card transition-shadow hover:shadow-tile"
          >
            <IconC className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
            <span>
              <span className="flex items-center gap-1 text-sm font-medium text-foreground">
                {label}
                <ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5" />
              </span>
              <span className="block text-xs text-muted-foreground">{description}</span>
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}

export interface AttentionItem {
  /** Stable id (test hook + React key). */
  id: string;
  /** Counted thing; an item with 0 is not shown. */
  count: number;
  text: string;
  href?: string;
  cta?: string;
}

/**
 * What needs someone's attention. Zero-count items are dropped; an empty list
 * says "nothing outstanding" — but sources that could not be read are named,
 * so a quiet list is not mistaken for a clean one.
 */
export function NeedsAttention({ items, unavailable }: { items: AttentionItem[]; unavailable?: string[] }) {
  const { t } = useTranslation();
  const open = items.filter((i) => i.count > 0);
  return (
    <section className="rounded-xl border bg-card p-5 shadow-card" data-testid="needs-attention">
      <h2 className="text-base font-semibold text-foreground">{t("overview.needsAttention")}</h2>
      {open.length === 0 ? (
        <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
          <CircleCheck className="h-4 w-4 text-emerald-600" />
          {unavailable && unavailable.length > 0 ? t("overview.nothingOutstandingChecked") : t("overview.nothingOutstanding")}
        </p>
      ) : (
        <ul className="mt-3 divide-y">
          {open.map((i) => (
            <li key={i.id} className="flex items-center justify-between gap-3 py-2.5" data-testid={`attention-${i.id}`}>
              <span className="flex items-start gap-2 text-sm">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
                <span>
                  <span className="font-semibold tabular-nums">{i.count.toLocaleString()}</span> {i.text}
                </span>
              </span>
              {i.href ? (
                <Button asChild size="sm" variant="ghost" className="shrink-0">
                  {i.href.startsWith("#") ? (
                    <a href={i.href}>
                      {i.cta ?? t("overview.review")} <ArrowRight className="h-3.5 w-3.5" />
                    </a>
                  ) : (
                    <Link to={i.href}>
                      {i.cta ?? t("overview.review")} <ArrowRight className="h-3.5 w-3.5" />
                    </Link>
                  )}
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {unavailable && unavailable.length > 0 ? (
        <p className="mt-3 text-xs text-muted-foreground" data-testid="attention-unavailable">
          {t("overview.couldNotBeChecked", { list: unavailable.join(", ") })}
        </p>
      ) : null}
    </section>
  );
}

export interface ActivityRow {
  id: string;
  at: string;
  event: string;
  actor?: string | null;
  target?: string | null;
  severity?: string | null;
}

export function RecentActivity({ rows, href, error, loading }: { rows: ActivityRow[]; href: string; error?: string | null; loading?: boolean }) {
  const { t } = useTranslation();
  return (
    <section className="rounded-xl border bg-card p-5 shadow-card" data-testid="recent-activity">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-base font-semibold text-foreground">{t("overview.recentActivity")}</h2>
        <Button asChild size="sm" variant="ghost">
          <Link to={href}>
            {t("overview.fullAuditLog")} <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </Button>
      </div>
      {loading ? (
        <p className="mt-3 text-sm text-muted-foreground">{t("common.loading")}</p>
      ) : error ? (
        <p className="mt-3 text-sm text-muted-foreground">{t("overview.auditUnreadable", { error })}</p>
      ) : rows.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground">{t("overview.noActivity")}</p>
      ) : (
        <ul className="mt-3 divide-y">
          {rows.map((r) => (
            <li key={r.id} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 py-2 text-sm">
              <span className="min-w-0">
                <span className="font-mono text-xs">{r.event}</span>
                {r.actor ? <span className="text-muted-foreground"> {t("overview.by", { actor: r.actor })}</span> : null}
                {r.target ? <span className="text-muted-foreground"> → {r.target}</span> : null}
                {r.severity && r.severity !== "INFO" ? (
                  <Badge variant={r.severity === "CRITICAL" ? "destructive" : "secondary"} className="ml-2 px-1 py-0 text-[10px]">
                    {r.severity}
                  </Badge>
                ) : null}
              </span>
              <time className="shrink-0 font-mono text-xs text-muted-foreground" dateTime={r.at}>
                {formatWhen(r.at)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function SectionCard({
  id,
  icon: IconC,
  title,
  badge,
  tone,
  children,
}: {
  id?: string;
  icon: Icon;
  title: string;
  badge?: ReactNode;
  tone?: "danger" | "warn";
  children: ReactNode;
}) {
  return (
    <section
      id={id}
      data-testid={id ? `section-${id}` : undefined}
      className={cn(
        "scroll-mt-4 rounded-xl border bg-card p-5 shadow-card",
        tone === "danger" && "border-destructive/50",
        tone === "warn" && "border-amber-500/50",
      )}
    >
      <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
        <IconC className="h-4 w-4 text-muted-foreground" />
        {title}
        {badge}
      </h2>
      <div className="mt-3">{children}</div>
    </section>
  );
}
