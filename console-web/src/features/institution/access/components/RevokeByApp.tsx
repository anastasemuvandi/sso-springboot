/** Flow A — start from an application: pick it → its holders in this institution → select → server preview → confirm. */
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useInstitution } from "../../context";
import { accessAppsQuery, appUsersQuery } from "../api";
import { clientFirstBody, MAX_REVOKE_USERS } from "../state/revokePreview";
import type { RevokeRequestBody } from "../types";
import { RevokeConfirm } from "./RevokeConfirm";

export function RevokeByApp({ initialClient = "" }: { initialClient?: string }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [clientId, setClientId] = useState(initialClient);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<RevokeRequestBody | null>(null);
  const apps = useQuery(accessAppsQuery(inst.scope));
  const holders = useQuery(appUsersQuery(inst.scope, clientId));

  const toggle = (id: string) =>
    setPicked((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <div className="space-y-4">
      <div className="max-w-md space-y-1.5">
        <Label htmlFor="revoke-app">{t("instAccess.revoke.pickApp")}</Label>
        {apps.isError ? (
          <ErrorState error={apps.error} />
        ) : (
          <select
            id="revoke-app"
            value={clientId}
            onChange={(e) => {
              setClientId(e.target.value);
              setPicked(new Set());
              setPreview(null);
            }}
            className="flex h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
          >
            <option value="">{t("instAccess.revoke.pickAppPlaceholder")}</option>
            {(apps.data ?? []).map((a) => (
              <option key={a.clientId} value={a.clientId}>
                {a.name && a.name !== a.clientId ? `${a.name} (${a.clientId})` : a.clientId}
              </option>
            ))}
          </select>
        )}
      </div>

      {!clientId ? null : holders.isPending ? (
        <Loading />
      ) : holders.isError ? (
        <ErrorState error={holders.error} onRetry={() => void holders.refetch()} />
      ) : holders.data.items.length === 0 ? (
        <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instAccess.revoke.noHolders")}</div>
      ) : (
        <>
          {!holders.data.revocable ? <p className="text-sm text-muted-foreground">{t("instAccess.revoke.notRevocable")}</p> : null}
          <div className="rounded-xl border bg-card shadow-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10" />
                  <TableHead>{t("instAccess.revoke.colUser")}</TableHead>
                  <TableHead>{t("instAccess.revoke.colEmail")}</TableHead>
                  <TableHead>{t("instAccess.revoke.colHow")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {holders.data.items.map((h) => (
                  <TableRow key={h.id}>
                    <TableCell>
                      <input
                        type="checkbox"
                        checked={picked.has(h.id)}
                        disabled={!holders.data.revocable || preview !== null}
                        onChange={() => toggle(h.id)}
                        aria-label={h.username}
                        className="h-4 w-4 rounded border-input accent-primary"
                      />
                    </TableCell>
                    <TableCell className="text-sm font-medium">
                      {h.username}
                      {!h.enabled ? (
                        <Badge variant="outline" className="ml-2 opacity-70">
                          {t("instAccess.revoke.disabled")}
                        </Badge>
                      ) : null}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">{h.email ?? "—"}</TableCell>
                    <TableCell className="space-x-1 text-xs">
                      {h.direct ? <Badge variant="secondary">{t("instAccess.revoke.direct")}</Badge> : null}
                      {h.via_groups.length > 0 ? <Badge variant="outline">{t("instAccess.revoke.viaGroups", { groups: h.via_groups.join(", ") })}</Badge> : null}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <p className="text-xs text-muted-foreground">{t("instAccess.revoke.groupNote")}</p>
          {preview === null ? (
            <div className="flex items-center justify-between gap-3">
              <p className={picked.size > MAX_REVOKE_USERS ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
                {picked.size > MAX_REVOKE_USERS ? t("instAccess.revoke.tooMany", { max: MAX_REVOKE_USERS }) : t("instAccess.revoke.pickAppsHint")}
              </p>
              <Button
                variant="outline"
                disabled={!holders.data.revocable || picked.size === 0 || picked.size > MAX_REVOKE_USERS}
                onClick={() => {
                  const b = clientFirstBody(clientId, [...picked], true);
                  if (b.ok) setPreview(b.body);
                }}
              >
                {t("instAccess.revoke.review")}
              </Button>
            </div>
          ) : (
            <RevokeConfirm
              preview={preview}
              allClients={false}
              real={() => {
                const b = clientFirstBody(clientId, [...picked], false);
                return b.ok ? b.body : null;
              }}
              onCancel={() => setPreview(null)}
              onDone={() => {
                setPicked(new Set());
                setPreview(null);
              }}
            />
          )}
        </>
      )}
    </div>
  );
}
