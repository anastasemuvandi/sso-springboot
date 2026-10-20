/**
 * "Which users lose which applications" — rendered from the server's
 * `preview: true` response, so what an admin confirms is what the same server
 * code will then do. Skips are shown, not filtered out.
 *
 * The one revoke summary of the institution console: used by
 * /institution/access/revoke (both flows) and /institution/apps/:clientId/users.
 */
import { useTranslation } from "react-i18next";

import { cn } from "@/lib/utils";

import { summarizeRevoke } from "../state/revokePreview";
import type { RevokeReport } from "../types";

export function RevokeSummary({ report, className }: { report: RevokeReport; className?: string }) {
  const { t } = useTranslation();
  const s = summarizeRevoke(report);
  return (
    <div className={cn("space-y-3", className)} data-testid="revoke-summary">
      <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1">
        <p className="text-sm">
          <span className="text-2xl font-semibold tabular-nums">{t("instAccess.revoke.summary.revocations", { count: s.totalRevocations })}</span>
        </p>
        <p className="text-sm text-muted-foreground">
          {t("instAccess.revoke.summary.meta", { users: s.usersAffected, clients: s.clientsConsidered })}
          {s.totalSkipped > 0 ? t("instAccess.revoke.summary.skipped", { count: s.totalSkipped }) : ""}
        </p>
      </div>
      {s.nothingToDo ? (
        <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">{t("instAccess.revoke.summary.nothing")}</p>
      ) : (
        <ul className="divide-y rounded-md border">
          {s.losing.map((u) => (
            <li key={u.user_id} className="px-3 py-2 text-sm">
              <span className="font-medium">{u.username ?? u.user_id}</span>{" "}
              <span className="text-muted-foreground">{t("instAccess.revoke.summary.loses", { count: u.revoked.length })}</span>
              <span className="ml-1 font-mono text-xs">{u.revoked.join(", ")}</span>
            </li>
          ))}
        </ul>
      )}
      {s.untouched.length > 0 ? (
        <p className="text-xs text-muted-foreground">{t("instAccess.revoke.summary.noChangeFor", { names: s.untouched.map((u) => u.username ?? u.user_id).join(", ") })}</p>
      ) : null}
      <p className="text-xs text-muted-foreground">{t("instAccess.revoke.summary.footer")}</p>
    </div>
  );
}
