import type { ReactNode } from "react";
import { AlertTriangle, Info, Lock } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { cn } from "@/lib/utils";
import { useInstitution, useInstitutionStatus } from "../context";
import type { Capability } from "../types";

function StateScreen({ title, detail, tone, children }: { title: string; detail?: string; tone: "neutral" | "warning"; children?: ReactNode }) {
  const Icon = tone === "warning" ? AlertTriangle : Info;
  return (
    <div className={cn("mx-auto max-w-xl rounded-xl border bg-card p-6 shadow-card", tone === "warning" && "border-amber-500/40")} role="alert">
      <div className="flex items-start gap-3">
        <Icon className={cn("mt-0.5 h-5 w-5 shrink-0", tone === "warning" ? "text-amber-500" : "text-muted-foreground")} />
        <div className="min-w-0 space-y-3">
          <h1 className="text-lg font-semibold tracking-tight">{title}</h1>
          {detail ? <p className="rounded-md bg-muted/60 px-3 py-2 font-mono text-xs text-muted-foreground">{detail}</p> : null}
          <div className="space-y-2 text-sm text-muted-foreground">{children}</div>
        </div>
      </div>
    </div>
  );
}

/**
 * Renders children only once /me and /permissions resolved; otherwise the two
 * "cannot serve you" screens (their remedies differ: repair a config file vs.
 * get appointed) or a loading/error state.
 */
export function InstitutionGate({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const s = useInstitutionStatus();
  switch (s.kind) {
    case "loading":
      return <Loading />;
    case "config_unavailable":
      return (
        <StateScreen tone="warning" title={t("institution.gate.configTitle")} detail={s.detail}>
          <p>{t("institution.gate.configBody")}</p>
        </StateScreen>
      );
    case "not_admin":
      return (
        <StateScreen tone="neutral" title={t("institution.gate.notAdminTitle")} detail={s.detail}>
          <p>{t("institution.gate.notAdminBody")}</p>
        </StateScreen>
      );
    case "error":
      return <ErrorState error={s.error} />;
    default:
      return <>{children}</>;
  }
}

/** "Not included in your delegated permissions" — names the missing capability. */
export function CapabilityGate({ capability, children }: { capability: Capability; children: ReactNode }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  if (inst.can(capability)) return <>{children}</>;
  return (
    <div className="rounded-xl border bg-card p-6 shadow-card" data-testid="capability-gate">
      <div className="flex items-start gap-3">
        <Lock className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" />
        <div className="space-y-3">
          <h2 className="text-base font-semibold">{t("institution.gate.capTitle")}</h2>
          <p className="text-sm text-muted-foreground">
            <Trans i18nKey="institution.gate.capBody" values={{ capability, institution: inst.ctx.institution }} components={{ code: <code className="rounded bg-muted px-1 font-mono text-xs" /> }} />
          </p>
          <dl className="space-y-1 text-xs text-muted-foreground">
            <div>
              <dt className="inline font-medium">{t("institution.gate.granted")} </dt>
              <dd className="inline font-mono">{inst.perms.capabilities.join(", ") || t("common.none")}</dd>
            </div>
            <div>
              <dt className="inline font-medium">{t("institution.gate.tiers")} </dt>
              <dd className="inline font-mono">{inst.perms.templates.join(", ") || t("common.none")}</dd>
            </div>
          </dl>
          <p className="text-xs text-muted-foreground">{t("institution.gate.capRemedy")}</p>
        </div>
      </div>
    </div>
  );
}
