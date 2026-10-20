/**
 * The "2FA" cell. The realm mode decides first (off asks nobody, all asks
 * everyone); only `per_client` reads the app's own setting (unset = required).
 * An institution admin may only REQUIRE it, on their own apps — turning it off
 * is a super admin's decision and the wrapper refuses it (403), so no such
 * control exists here.
 */
import { useTranslation } from "react-i18next";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { InstitutionApp } from "../../types";

export function TwoFactorCell({ app, canRequire, onRequire, busy }: { app: InstitutionApp; canRequire: boolean; onRequire: () => void; busy?: boolean }) {
  const { t } = useTranslation();
  if (app.mfa_mode === null || app.mfa_mode === undefined) {
    return (
      <span className="text-xs text-muted-foreground" title={t("instApps.mfa.unknownHint")}>
        n/a
      </span>
    );
  }
  if (app.mfa_mode === "off") {
    return (
      <span className="text-xs text-muted-foreground" title={t("instApps.mfa.offHint")}>
        {t("instApps.mfa.off")}
      </span>
    );
  }
  if (app.mfa_mode === "all" || app.mfa_required) {
    return (
      <Badge variant="secondary" title={app.mfa_mode === "all" ? t("instApps.mfa.allHint") : t("instApps.mfa.requiredHint")}>
        {t("instApps.mfa.required")}
      </Badge>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Badge variant="outline" title={t("instApps.mfa.notRequiredHint")}>
        {t("instApps.mfa.notRequired")}
      </Badge>
      {canRequire ? (
        <Button size="sm" variant="outline" onClick={onRequire} disabled={busy}>
          {t("instApps.mfa.require")}
        </Button>
      ) : null}
    </div>
  );
}
