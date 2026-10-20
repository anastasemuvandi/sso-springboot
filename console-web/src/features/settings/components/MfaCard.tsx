/**
 * Realm-wide TOTP: the three-way mode with a confirmation that says what the
 * change will do (incl. the one-time bulk CONFIGURE_TOTP), and the exemption
 * list. Choosing an option changes nothing by itself; the selected option
 * always renders from the server's value.
 *
 * Controls are shown to super admins only — cosmetic: the wrapper refuses
 * PUT /realm/mfa and the exemption writes to anyone else.
 */
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { PageSection } from "./PageSection";
import { ConfigRow } from "./ConfigRow";
import { ExemptionsPanel } from "./ExemptionsPanel";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { MfaMode } from "@/features/clients/types";
import { clientKeys } from "@/features/clients/api";
import { cn, formatWhen } from "@/lib/utils";
import { realmMfaQuery, setRealmMfaMode, settingsKeys } from "../api";
import { describeModeChange, MFA_MODES } from "../state/mfaMode";

export function MfaCard({ canChange }: { canChange: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const mfa = useQuery(realmMfaQuery());
  const [target, setTarget] = useState<MfaMode | null>(null);

  const change = useMutation({
    mutationFn: (mode: MfaMode) => setRealmMfaMode(mode),
    onSuccess: (r) => {
      const words = t(`settings.mfa.modeWords.${r.mode}`);
      let msg = r.changed ? t("settings.mfa.nowMode", { words }) : t("settings.mfa.alreadyMode", { words });
      if (r.bulk_enrol?.started) msg += ` ${t("settings.mfa.bulkStarted")}`;
      notify.success(msg);
      void qc.invalidateQueries({ queryKey: settingsKeys.realmMfa() });
      void qc.invalidateQueries({ queryKey: clientKeys.all });
    },
    onError: (e) => notify.error(e),
    onSettled: () => setTarget(null),
  });

  return (
    <PageSection id="mfa" title={t("settings.mfa.title")} description={t("settings.mfa.description")}>
      {mfa.isPending ? (
        <Loading />
      ) : mfa.isError ? (
        <ErrorState error={mfa.error} onRetry={() => void mfa.refetch()} />
      ) : (
        <div className="space-y-4 text-sm">
          <div className="space-y-1">
            <ConfigRow label={t("settings.mfa.mode")} value={<Badge variant={mfa.data.mode === "off" ? "outline" : "secondary"} data-testid="mfa-current-mode">{t(`settings.mfa.modes.${mfa.data.mode}.label`)}</Badge>} />
            <ConfigRow
              label="mfa_totp_mode"
              value={mfa.data.raw_mode ?? t("settings.mfa.rawUnset", { legacy: mfa.data.raw ?? t("settings.mfa.unset"), mode: mfa.data.mode })}
            />
            <ConfigRow
              label={t("settings.mfa.browserFlow")}
              value={
                <span className="flex flex-wrap items-center gap-2">
                  <code className="text-xs">{mfa.data.browser_flow}</code>
                  {mfa.data.flow_ready ? (
                    <Badge variant="secondary">browser-totp</Badge>
                  ) : (
                    <Badge variant="destructive" data-testid="mfa-flow-not-ready">
                      {t("settings.mfa.flowNotReady")}
                    </Badge>
                  )}
                </span>
              }
            />
            <ConfigRow label={t("settings.mfa.firstEnabled")} value={mfa.data.first_enabled_at ? formatWhen(mfa.data.first_enabled_at) : t("settings.mfa.neverEnabled")} />
            <ConfigRow label={t("settings.mfa.exemptCount")} value={mfa.data.exempt_count ?? null} />
          </div>

          {canChange ? (
            <div className="space-y-2 rounded-md border px-3 py-3">
              <div id="mfa-mode-label" className="text-sm font-medium">
                {t("settings.mfa.require")}
              </div>
              <div role="radiogroup" aria-labelledby="mfa-mode-label" className="grid gap-2 sm:grid-cols-3">
                {MFA_MODES.map((m) => {
                  const active = m === mfa.data.mode;
                  return (
                    <button
                      key={m}
                      type="button"
                      role="radio"
                      aria-checked={active}
                      onClick={() => (active ? undefined : setTarget(m))}
                      className={cn(
                        "rounded-md border px-3 py-2 text-left text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                        active ? "border-primary bg-primary/10 font-medium" : "text-muted-foreground hover:bg-muted",
                      )}
                    >
                      <div>{t(`settings.mfa.modes.${m}.label`)}</div>
                      <div className="text-xs font-normal text-muted-foreground">{t(`settings.mfa.modes.${m}.hint`)}</div>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{t("settings.mfa.superAdminOnly")}</p>
          )}

          <ExemptionsPanel canChange={canChange} />

          {target ? (
            <ModeDialog
              target={target}
              change={describeModeChange(mfa.data, target)}
              pending={change.isPending}
              onCancel={() => setTarget(null)}
              onConfirm={() => change.mutate(target)}
              firstEnabledAt={mfa.data.first_enabled_at ?? null}
            />
          ) : null}
        </div>
      )}
    </PageSection>
  );
}

function ModeDialog({
  target,
  change,
  pending,
  onCancel,
  onConfirm,
  firstEnabledAt,
}: {
  target: MfaMode;
  change: ReturnType<typeof describeModeChange>;
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  firstEnabledAt: string | null;
}) {
  const { t } = useTranslation();
  return (
    <Dialog open onOpenChange={(open) => (!open ? onCancel() : undefined)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t(`settings.mfa.dialog.${target}.title`)}</DialogTitle>
          <DialogDescription asChild>
            <div className="space-y-2 text-sm text-muted-foreground" data-testid="mfa-consequences">
              {change.consequenceKeys.map((k) => (
                <p key={k} className={k.endsWith("flowNotReady") ? "text-destructive" : undefined}>
                  {t(k, { when: firstEnabledAt ? formatWhen(firstEnabledAt) : "" })}
                </p>
              ))}
            </div>
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel}>
            {t("common.cancel")}
          </Button>
          <Button variant={target === "off" ? "destructive" : "default"} onClick={onConfirm} disabled={pending} data-testid="mfa-confirm">
            {t(`settings.mfa.dialog.${target}.confirm`)}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
