/**
 * Step 1 of the matrix: pick the applications (columns). Only apps this
 * institution may use are offered — its own (type A) and the cross-cutting
 * ones shared with it (type C); the wrapper never lists platform clients to a
 * delegated admin. A view-only app is offered and renders read-only.
 */
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useInstitution } from "../../context";
import { accessAppsQuery } from "../api";

/** app/core/access_matrix.py MAX_COLUMNS */
export const MAX_COLUMNS = 25;

export function AppPicker({ initial, onOpen }: { initial: string[]; onOpen: (clientIds: string[]) => void }) {
  const { t } = useTranslation();
  const { scope } = useInstitution();
  const apps = useQuery(accessAppsQuery(scope));
  const [picked, setPicked] = useState<Set<string>>(() => new Set(initial));

  if (apps.isPending) return <Loading />;
  if (apps.isError) return <ErrorState error={apps.error} onRetry={() => void apps.refetch()} />;

  const sorted = [...apps.data].sort((a, b) => (a.name || a.clientId).localeCompare(b.name || b.clientId));
  const groups = [
    { title: t("instAccess.matrix.pickOwn"), hint: t("instAccess.matrix.pickOwnHint"), rows: sorted.filter((a) => !a.cross_cutting) },
    { title: t("instAccess.matrix.pickShared"), hint: t("instAccess.matrix.pickSharedHint"), rows: sorted.filter((a) => a.cross_cutting) },
  ];
  const toggle = (id: string) =>
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const tooMany = picked.size > MAX_COLUMNS;

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (picked.size > 0 && !tooMany) onOpen([...picked]);
      }}
    >
      {groups.map((g) =>
        g.rows.length === 0 ? null : (
          <fieldset key={g.title} className="rounded-xl border bg-card p-4 shadow-card">
            <legend className="px-1 text-sm font-semibold">
              {g.title} <span className="font-normal text-muted-foreground">· {g.hint}</span>
            </legend>
            <div className="mt-2 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {g.rows.map((app) => (
                <label key={app.clientId} className="flex cursor-pointer items-start gap-2 rounded-md border px-3 py-2 hover:bg-accent">
                  <input type="checkbox" checked={picked.has(app.clientId)} onChange={() => toggle(app.clientId)} className="mt-0.5 h-4 w-4 rounded border-input accent-primary" aria-label={app.clientId} />
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium">{app.name || app.clientId}</span>
                    <span className="block truncate font-mono text-xs text-muted-foreground">{app.clientId}</span>
                    {!app.manageable ? (
                      <Badge variant="outline" className="mt-1 opacity-70">
                        {t("instAccess.matrix.viewOnly")}
                      </Badge>
                    ) : null}
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        ),
      )}
      {sorted.length === 0 ? (
        <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instAccess.matrix.noApps")}</div>
      ) : (
        <div className="flex items-center justify-between gap-3">
          <p className={tooMany ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
            {tooMany ? t("instAccess.matrix.tooMany", { max: MAX_COLUMNS }) : t("instAccess.matrix.pickHint", { max: MAX_COLUMNS })}
          </p>
          <Button type="submit" disabled={picked.size === 0 || tooMany}>
            {t("instAccess.matrix.open")}
          </Button>
        </div>
      )}
    </form>
  );
}
