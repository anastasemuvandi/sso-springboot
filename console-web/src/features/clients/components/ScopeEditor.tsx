/**
 * Institution scope card on /clients/:id.
 *
 * Save flow (`scopeSaveReducer`): PUT without `confirm` → the wrapper answers
 * 409 when the change removes an institution that still has users → a dialog
 * shows the wrapper's own numbers → Confirm re-sends with `confirm: true`
 * (which also withdraws that institution's access); Cancel sends nothing.
 * The inline preview uses the snapshot from GET …/scope; the 409 is the rule.
 */
import { useEffect, useReducer, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { clientKeys, clientScopeQuery, clientsApi, institutionOptionsQuery } from "../api";
import { formFromScope, parseScopeConflict, scopeRequest, scopeSaveReducer, type ScopeFormValues } from "../state/clientScopeForm";
import type { ClientScope, ClientScopeFields as Fields } from "../types";
import { ClientScopeFields } from "./ClientScopeFields";

function ScopeBadge({ scope }: { scope: ClientScope }) {
  const { t } = useTranslation();
  if (scope.kind === "cross-cutting") {
    return (
      <Badge variant="secondary">
        {scope.all_institutions ? t("clients.scope.allInstitutions") : t("clients.scope.nInstitutions", { count: scope.institutions.length })}
      </Badge>
    );
  }
  if (scope.kind === "institution") return <Badge variant="outline">{scope.owner_institution}</Badge>;
  return <Badge variant="destructive">{t("clients.scope.platformInternal")}</Badge>;
}

export function ScopeEditor({ clientId }: { clientId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const scopeQ = useQuery(clientScopeQuery(clientId));
  const instQ = useQuery(institutionOptionsQuery());
  const [values, setValues] = useState<ScopeFormValues | null>(null);
  const [state, dispatch] = useReducer(scopeSaveReducer, { phase: "idle" });

  useEffect(() => {
    if (scopeQ.data && values === null) setValues(formFromScope(scopeQ.data.scope));
  }, [scopeQ.data, values]);

  const save = useMutation({
    mutationFn: ({ fields, confirm }: { fields: Fields; confirm: boolean }) => clientsApi.setScope(clientId, fields, confirm),
    onSuccess: (change) => {
      dispatch({ type: "saved", removed: change.removed_institutions ?? [] });
      notify.success(
        change.removed_institutions?.length
          ? t("clients.scopeEditor.savedRemoved", { list: change.removed_institutions.join(", ") })
          : t("clients.scopeEditor.saved"),
      );
      setValues(formFromScope(change.scope));
      void qc.invalidateQueries({ queryKey: clientKeys.all });
    },
    onError: (e, vars) => {
      const conflict = parseScopeConflict(e);
      if (conflict) {
        dispatch({ type: "conflict", conflict, fields: vars.fields });
        if (vars.confirm) notify.error(e);
        return;
      }
      dispatch({ type: "failed", message: errorMessage(e) });
      notify.error(e);
    },
  });

  function submit() {
    if (!values) return;
    const req = scopeRequest(values);
    if (!req.ok) return;
    dispatch({ type: "submit" });
    save.mutate({ fields: req.fields, confirm: false });
  }

  function confirmSave() {
    if (state.phase !== "confirming") return;
    const fields = state.fields;
    dispatch({ type: "confirm" });
    save.mutate({ fields, confirm: true });
  }

  const request = values ? scopeRequest(values) : null;
  const scope = scopeQ.data?.scope;

  return (
    <Card id="scope" className="scroll-mt-24">
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle>{t("clients.scopeEditor.title")}</CardTitle>
          {scope ? <ScopeBadge scope={scope} /> : null}
        </div>
        <CardDescription>{t("clients.scopeEditor.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {scopeQ.isPending ? (
          <Loading />
        ) : scopeQ.isError ? (
          <ErrorState error={scopeQ.error} title={t("clients.scopeEditor.loadFailed")} onRetry={() => void scopeQ.refetch()} />
        ) : values ? (
          <>
            {scopeQ.data.scope.inferred ? (
              <p className="rounded-md border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
                {t("clients.scopeEditor.inferred", { prefix: `${scopeQ.data.scope.owner_institution ?? ""}-` })}
              </p>
            ) : null}
            {instQ.isError ? <p className="text-xs text-destructive">{t("clients.scopeEditor.institutionsFailed", { error: errorMessage(instQ.error) })}</p> : null}
            <ClientScopeFields
              institutions={instQ.data ?? []}
              value={values}
              onChange={setValues}
              access={scopeQ.data.access}
              disabled={save.isPending}
            />
            <div className="flex justify-end">
              <Button onClick={submit} disabled={!request?.ok || save.isPending} data-testid="scope-save">
                {save.isPending ? t("common.saving") : t("clients.scopeEditor.save")}
              </Button>
            </div>
          </>
        ) : null}
      </CardContent>

      <Dialog open={state.phase === "confirming"} onOpenChange={(open) => !open && dispatch({ type: "cancel" })}>
        <DialogContent data-testid="scope-confirm-dialog">
          <DialogHeader>
            <DialogTitle>{t("clients.scopeEditor.confirmTitle")}</DialogTitle>
            <DialogDescription>{state.phase === "confirming" ? state.conflict.message : ""}</DialogDescription>
          </DialogHeader>
          {state.phase === "confirming" ? (
            <div className="space-y-2 text-sm">
              <p>{t("clients.scopeEditor.confirmRemoved", { list: state.conflict.removedInstitutions.join(", ") || "—" })}</p>
              {state.conflict.accessAtRisk.length > 0 ? (
                <ul className="list-disc space-y-1 pl-5" data-testid="scope-at-risk">
                  {state.conflict.accessAtRisk.map((r) => (
                    <li key={r.institution}>
                      <span className="font-mono">{r.institution}</span> — {t("clients.scopeForm.impact", { count: r.users })}
                      {r.group_assigned ? ` · ${t("clients.scopeForm.groupAssigned")}` : ""}
                    </li>
                  ))}
                </ul>
              ) : null}
              <p className="text-muted-foreground">{t("clients.scopeEditor.confirmExplain")}</p>
            </div>
          ) : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => dispatch({ type: "cancel" })}>
              {t("common.cancel")}
            </Button>
            <Button variant="destructive" onClick={confirmSave} data-testid="scope-confirm">
              {t("clients.scopeEditor.confirmButton")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
