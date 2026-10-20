/**
 * Per-client TOTP (read in the realm's per_client mode). The buttons show for
 * super admins only — cosmetic; the wrapper refuses anyone else's PUT.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { useSession } from "@/auth/session";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { settingsKeys } from "@/features/settings/api";
import { clientKeys, clientMfaQuery, clientsApi } from "../api";

export function MfaCard({ clientId }: { clientId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const session = useSession();
  const isSuperAdmin = (session.roles ?? []).includes("super_admin");
  const q = useQuery(clientMfaQuery(clientId));

  const set = useMutation({
    mutationFn: (required: boolean) => clientsApi.setMfa(clientId, required),
    onSuccess: (r) => {
      const state = r.required ? t("clients.mfa.requiredWord") : t("clients.mfa.notRequiredWord");
      notify.success(
        !r.changed
          ? t("clients.mfa.already", { state, clientId })
          : r.mode === "per_client"
            ? t("clients.mfa.now", { state, clientId })
            : t("clients.mfa.nowOtherMode", { state, clientId, mode: r.mode }),
      );
      qc.setQueryData(clientKeys.mfa(clientId), r);
      void qc.invalidateQueries({ queryKey: clientKeys.mfa(clientId) });
      void qc.invalidateQueries({ queryKey: clientKeys.list() });
      void qc.invalidateQueries({ queryKey: settingsKeys.realmMfa() });
    },
    onError: (e) => notify.error(e),
  });

  async function change(required: boolean) {
    const ok = await confirm({
      title: required ? t("clients.mfa.requireTitle") : t("clients.mfa.stopTitle"),
      description: required ? t("clients.mfa.requireConfirm", { clientId }) : t("clients.mfa.stopConfirm", { clientId }),
      destructive: !required,
      confirmLabel: required ? t("clients.mfa.require") : t("clients.mfa.stop"),
    });
    if (ok) set.mutate(required);
  }

  const m = q.data;
  const modeLabel = m ? t(`clients.mfa.mode.${m.mode}`) : "";
  return (
    <Card id="mfa" className="scroll-mt-24">
      <CardHeader>
        <CardTitle>{t("clients.mfa.title")}</CardTitle>
        <CardDescription>{t("clients.mfa.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {q.isPending ? (
          <Loading />
        ) : q.isError ? (
          <ErrorState error={q.error} title={t("clients.mfa.loadFailed")} />
        ) : m ? (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-muted-foreground">{t("clients.mfa.thisClient")}</span>
              <Badge variant={m.required ? "secondary" : "outline"} data-testid="client-mfa-required">
                {m.required ? t("clients.mfa.requires") : t("clients.mfa.doesNotRequire")}
              </Badge>
              {m.raw === null || m.raw === undefined || m.raw === "" ? <span className="text-xs text-muted-foreground">{t("clients.mfa.unsetDefault")}</span> : null}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-muted-foreground">{t("clients.mfa.rightNow")}</span>
              <Badge variant={m.applies_now ? "secondary" : "outline"}>{m.applies_now ? t("clients.mfa.codeAsked") : t("clients.mfa.passwordOnly")}</Badge>
              <span className="text-xs text-muted-foreground">{t("clients.mfa.realmMode", { mode: modeLabel })}</span>
            </div>
            {isSuperAdmin ? (
              <div className="flex flex-wrap gap-2 pt-1">
                {m.required ? (
                  <Button variant="destructive" onClick={() => void change(false)} disabled={set.isPending}>
                    {t("clients.mfa.stop")}
                  </Button>
                ) : (
                  <Button variant="outline" onClick={() => void change(true)} disabled={set.isPending}>
                    {t("clients.mfa.require")}
                  </Button>
                )}
              </div>
            ) : (
              <p className="text-xs text-muted-foreground">{t("clients.mfa.superAdminOnly")}</p>
            )}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
