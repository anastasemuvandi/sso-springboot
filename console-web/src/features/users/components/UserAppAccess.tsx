/**
 * App access for one user: every client, in the state the login gate's own
 * rule gives (granted / open / revoked / not_granted / exempt / unknown).
 *
 * Grant = admitUser (named grant, lifts a denial); revoke = deny-list (beats
 * every grant, leaves institution membership alone); restore = grant on a
 * revoked row. Every change carries the state this page showed; the wrapper
 * answers 409 `state_changed` (nothing written) if it moved — we then re-read.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { ApiError } from "@/api/errors";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Hint } from "@/components/ui/tooltip";
import { changeClientAccess, userClientAccessQuery, userKeys } from "../api";
import { accessStateView, availableActions, countByState, viaGroupsText, wireAction, type AccessAction } from "../state/clientAccessState";
import type { UserClientAccess, UserClientAccessItem } from "../types";

export function UserAppAccess({ userId, username }: { userId: string; username?: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const access = useQuery(userClientAccessQuery(userId));

  const change = useMutation({
    mutationFn: ({ item, action }: { item: UserClientAccessItem; action: AccessAction }) =>
      changeClientAccess(userId, item.client_id, wireAction(action), item.state),
    onSuccess: (res, { item, action }) => {
      // Show the gate's new answer right away, then re-read the whole list.
      qc.setQueryData<UserClientAccess>(userKeys.clientAccess(userId), (prev) =>
        prev ? { ...prev, items: prev.items.map((i) => (i.client_id === item.client_id ? res.item : i)) } : prev,
      );
      notify.success(t(`users.access.done.${action}`, { client: item.client_id }));
      void qc.invalidateQueries({ queryKey: userKeys.clientAccess(userId) });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409 && e.code === "state_changed") {
        notify.error(t("users.access.stateChanged"));
      } else {
        notify.error(e);
      }
      void qc.invalidateQueries({ queryKey: userKeys.clientAccess(userId) });
    },
  });

  async function run(item: UserClientAccessItem, action: AccessAction) {
    if (action === "revoke") {
      const ok = await confirm({
        title: t("users.access.revokeTitle", { client: item.client_id }),
        description: t("users.access.revokeBody", { user: username ?? userId }),
        confirmLabel: t("users.access.actions.revoke"),
        destructive: true,
      });
      if (!ok) return;
    }
    change.mutate({ item, action });
  }

  const items = access.data?.items ?? [];
  const counts = countByState(items);

  return (
    <Card data-testid="user-app-access">
      <CardHeader>
        <CardTitle>{t("users.access.title")}</CardTitle>
        <CardDescription>{t("users.access.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {access.isPending ? (
          <Loading />
        ) : access.isError ? (
          <ErrorState error={access.error} title={t("users.access.loadFailed")} onRetry={() => void access.refetch()} />
        ) : items.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("users.access.noClients")}</p>
        ) : (
          <>
            <p className="text-xs text-muted-foreground">
              {t("users.access.summary", { granted: counts.granted + counts.open, revoked: counts.revoked, notGranted: counts.not_granted })}
            </p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("common.client")}</TableHead>
                  <TableHead>{t("common.status")}</TableHead>
                  <TableHead>{t("users.access.how")}</TableHead>
                  <TableHead className="text-right">{t("common.actions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => {
                  const view = accessStateView(item.state);
                  const deniedViaGroup = item.state === "revoked" && item.denied_groups.length > 0;
                  const actions = deniedViaGroup ? [] : availableActions(view.state);
                  return (
                    <TableRow key={item.client_uuid || item.client_id} data-testid={`access-row-${item.client_id}`}>
                      <TableCell>
                        <Link to={`/clients/${encodeURIComponent(item.client_id)}`} className="font-mono text-xs hover:underline">
                          {item.client_id}
                        </Link>
                        {item.name && item.name !== item.client_id ? <span className="ml-2 text-xs text-muted-foreground">{item.name}</span> : null}
                      </TableCell>
                      <TableCell>
                        <Hint content={t(view.helpKey)}>
                          <span className="inline-flex">
                            <Badge variant={view.tone} data-testid="access-state">
                              {t(view.labelKey)}
                            </Badge>
                          </span>
                        </Hint>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">{howText(item, t)}</TableCell>
                      <TableCell className="text-right">
                        {deniedViaGroup ? (
                          <span className="text-xs text-muted-foreground">{t("users.access.deniedViaGroup")}</span>
                        ) : null}
                        {actions.map((a) => (
                          <Button
                            key={a}
                            size="sm"
                            variant={a === "revoke" ? "ghost" : "outline"}
                            className={a === "revoke" ? "text-destructive hover:text-destructive" : undefined}
                            disabled={change.isPending}
                            onClick={() => void run(item, a)}
                          >
                            {t(`users.access.actions.${a}`)}
                          </Button>
                        ))}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function howText(item: UserClientAccessItem, t: (k: string, o?: Record<string, unknown>) => string): string {
  const how: string[] = [];
  if (item.state === "revoked") {
    how.push(
      item.denied_groups.length
        ? t("users.access.howRevokedGroup", { groups: item.denied_groups.map((g) => g.name ?? g.id).join(", ") })
        : t("users.access.howRevokedPerson"),
    );
  }
  if (item.state === "open") how.push(t("users.access.howOpen"));
  if (item.direct) how.push(t("users.access.howDirect"));
  if (item.via_groups.length) how.push(t("users.access.howVia", { groups: viaGroupsText(item) }));
  if (item.state === "unknown" && item.error) how.push(item.error);
  return how.join(" · ") || "—";
}
