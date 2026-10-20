/**
 * Join key card on /clients/:id — which identifier the application finds its
 * own users by, carried in the `join_key` claim.
 *
 * Save is a round trip: PUT without `confirm`; when the client has live
 * sessions or an already-configured client has assigned users, the wrapper
 * answers 409 with both counts; the admin confirms in a dialog and the PUT is
 * re-sent with `confirm: true`. A first-time set is not gated.
 * "Check assigned users" reads `?check=true` (a read per assigned user).
 */
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { clientKeys, clientsApi, joinColumnQuery, parseJoinColumnConflict, type JoinColumnConflict } from "../api";
import type { JoinColumn, JoinColumnChange } from "../types";

export function JoinColumnEditor({ clientId }: { clientId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [check, setCheck] = useState(false);
  const q = useQuery(joinColumnQuery(clientId, check));
  const [choice, setChoice] = useState<JoinColumn | "">("");
  const [conflict, setConflict] = useState<(JoinColumnConflict & { column: JoinColumn }) | null>(null);

  useEffect(() => {
    if (q.data && choice === "") setChoice(q.data.setting.join_column);
  }, [q.data, choice]);

  const save = useMutation({
    mutationFn: ({ column, confirm }: { column: JoinColumn; confirm: boolean }) => clientsApi.setJoinColumn(clientId, column, confirm),
    onSuccess: (change: JoinColumnChange) => {
      setConflict(null);
      notify.success(
        change.readiness?.blocked > 0
          ? t("clients.joinEditor.savedBlocked", { column: change.setting.join_column, mapper: change.mapper, count: change.readiness.blocked })
          : t("clients.joinEditor.saved", { column: change.setting.join_column, mapper: change.mapper }),
      );
      void qc.invalidateQueries({ queryKey: clientKeys.joinColumnAny(clientId) });
      void qc.invalidateQueries({ queryKey: clientKeys.list() });
    },
    onError: (e, vars) => {
      const c = !vars.confirm ? parseJoinColumnConflict(e) : null;
      if (c) {
        setConflict({ ...c, column: vars.column });
        return;
      }
      setConflict(null);
      notify.error(e);
    },
  });

  const jk = q.data;
  const readiness = jk?.readiness ?? null;

  return (
    <Card id="join-key" className="scroll-mt-24">
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle>{t("clients.joinEditor.title")}</CardTitle>
          {jk ? (
            <Badge variant={jk.setting.explicit ? "secondary" : "outline"}>
              {jk.setting.join_column}
              {jk.setting.explicit ? "" : ` ${t("clients.joinEditor.defaultSuffix")}`}
            </Badge>
          ) : null}
        </div>
        <CardDescription>{t("clients.joinEditor.description", { claim: jk?.setting.claim ?? "join_key" })}</CardDescription>
      </CardHeader>
      {q.isPending ? (
        <CardContent>
          <Loading />
        </CardContent>
      ) : q.isError ? (
        <CardContent>
          <ErrorState error={q.error} title={t("clients.joinEditor.loadFailed")} onRetry={() => void q.refetch()} />
        </CardContent>
      ) : jk ? (
        <>
          <CardContent className="space-y-4">
            {!jk.setting.explicit ? (
              <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">{t("clients.joinEditor.unsetExplain")}</p>
            ) : null}
            {jk.setting.malformed_value ? (
              <p className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-sm">
                {t("clients.joinEditor.malformed", { value: jk.setting.malformed_value })}
              </p>
            ) : null}

            <div className="space-y-2" role="radiogroup" aria-label={t("clients.joinEditor.identifyBy")}>
              <Label>{t("clients.joinEditor.identifyBy")}</Label>
              {jk.choices.map((c) => (
                <label key={c.value} className="flex cursor-pointer items-start gap-3 rounded-md border p-3 hover:bg-muted/40">
                  <input type="radio" name="join_column" value={c.value} checked={choice === c.value} onChange={() => setChoice(c.value)} className="mt-1" />
                  <span className="text-sm">
                    <span className="font-medium">{c.label}</span>
                    {c.default ? <span className="ml-2 text-xs text-muted-foreground">{t("clients.joinEditor.platformDefault")}</span> : null}
                    <span className="block text-xs text-muted-foreground">
                      {t("clients.joinEditor.userAttribute")} <code className="font-mono">{c.source}</code> · {c.shape}
                    </span>
                  </span>
                </label>
              ))}
            </div>

            <div className="rounded-md border bg-muted/40 px-3 py-2.5 text-xs leading-relaxed">
              <p className="font-medium text-foreground">
                {jk.active_sessions < 0 ? t("clients.joinEditor.sessionsUnknown") : t("clients.joinEditor.sessions", { count: jk.active_sessions })}
              </p>
              <p className="mt-1 text-muted-foreground">{t("clients.joinEditor.sessionsExplain")}</p>
              {jk.setting.changed_by || jk.setting.changed_at ? (
                <p className="mt-2 text-muted-foreground">
                  {t("clients.joinEditor.lastChanged", { who: jk.setting.changed_by ?? "?", when: jk.setting.changed_at ?? "?" })}
                </p>
              ) : null}
            </div>

            {readiness ? (
              <div className="space-y-2" data-testid="join-readiness">
                <Label>{t("clients.joinEditor.blocks", { blocked: readiness.blocked, checked: readiness.checked })}</Label>
                {readiness.truncated ? <p className="text-xs text-muted-foreground">{t("clients.joinEditor.truncated", { checked: readiness.checked })}</p> : null}
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("common.user")}</TableHead>
                      <TableHead>{t("clients.joinEditor.problem")}</TableHead>
                      <TableHead>{t("common.details")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {readiness.users.map((u) => (
                      <TableRow key={u.user_id}>
                        <TableCell className="font-mono text-xs">
                          <Link to={`/users/${u.user_id}`} className="hover:underline">
                            {u.username ?? u.user_id}
                          </Link>
                        </TableCell>
                        <TableCell className="text-xs">
                          <code className="font-mono">{u.error}</code>
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">{u.detail}</TableCell>
                      </TableRow>
                    ))}
                    {readiness.users.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={3} className="py-4 text-center text-sm text-muted-foreground">
                          {t("clients.joinEditor.allUsable")}
                        </TableCell>
                      </TableRow>
                    ) : null}
                  </TableBody>
                </Table>
              </div>
            ) : null}
          </CardContent>
          <CardFooter className="flex justify-between gap-2">
            {readiness ? (
              <span className="text-xs text-muted-foreground">{t("clients.joinEditor.checkedAgainst")}</span>
            ) : (
              <Button variant="outline" onClick={() => setCheck(true)} disabled={q.isFetching}>
                {t("clients.joinEditor.check")}
              </Button>
            )}
            <Button onClick={() => choice && save.mutate({ column: choice, confirm: false })} disabled={!choice || save.isPending} data-testid="join-save">
              {save.isPending ? t("common.saving") : t("clients.joinEditor.save")}
            </Button>
          </CardFooter>
        </>
      ) : null}

      <Dialog open={conflict !== null} onOpenChange={(open) => !open && setConflict(null)}>
        <DialogContent data-testid="join-confirm-dialog">
          <DialogHeader>
            <DialogTitle>{t("clients.joinEditor.confirmTitle", { column: conflict?.column ?? "" })}</DialogTitle>
            <DialogDescription>{conflict?.message}</DialogDescription>
          </DialogHeader>
          {conflict ? (
            <ul className="list-disc space-y-1 pl-5 text-sm">
              <li>{t("clients.joinEditor.sessions", { count: conflict.activeSessions })}</li>
              <li>{t("clients.joinEditor.assignedUsers", { count: conflict.assignedUsers })}</li>
            </ul>
          ) : null}
          <p className="text-xs text-muted-foreground">{t("clients.joinEditor.confirmExplain")}</p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConflict(null)}>
              {t("common.cancel")}
            </Button>
            <Button variant="destructive" onClick={() => conflict && save.mutate({ column: conflict.column, confirm: true })} disabled={save.isPending}>
              {t("clients.joinEditor.confirmButton")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
