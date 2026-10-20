/** Small read-only cells shared by the client list and the join-key worklist. */
import { Link } from "react-router-dom";
import { Pencil } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Badge } from "@/components/ui/badge";
import { Hint } from "@/components/ui/tooltip";
import type { MfaStatus } from "../state/mfaStatus";
import type { Client, ClientScope } from "../types";

/**
 * Which institutions may be GIVEN this client (distinct from who has it).
 * `platform` is the absence of an institution: no delegated admin sees it.
 */
export function ScopeCell({ scope }: { scope?: ClientScope }) {
  const { t } = useTranslation();
  if (!scope || scope.kind === "platform") {
    return (
      <span className="text-xs text-muted-foreground" title={t("clients.scope.platformHint")}>
        {t("clients.scope.platform")}
      </span>
    );
  }
  if (scope.kind === "institution") {
    return (
      <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs" title={scope.inferred ? t("clients.scope.inferredHint") : undefined}>
        {scope.owner_institution}
        {scope.inferred ? "*" : ""}
      </span>
    );
  }
  return (
    <span
      className="text-xs"
      title={scope.all_institutions ? t("clients.scope.allHint") : t("clients.scope.someHint", { list: scope.institutions.join(", ") })}
    >
      {scope.all_institutions ? t("clients.scope.allInstitutions") : t("clients.scope.nInstitutions", { count: scope.institutions.length })}
    </span>
  );
}

/**
 * The client's join column. An unconfigured client is FLAGGED, not described
 * as "email": nobody chose it, it has no mapper and provisioning refuses it.
 */
export function JoinKeyBadge({ client, withChanger = false }: { client: Client; withChanger?: boolean }) {
  const { t } = useTranslation();
  const jc = client.join_column;
  if (jc?.malformed_value) {
    return (
      <Badge variant="destructive" title={t("clients.joinKey.malformedHint", { value: jc.malformed_value })}>
        {t("clients.joinKey.invalid")}
      </Badge>
    );
  }
  if (!jc?.explicit) {
    return (
      <Badge variant="destructive" title={t("clients.joinKey.notSetHint")}>
        {t("clients.joinKey.notSet")}
      </Badge>
    );
  }
  return (
    <span className="flex items-baseline gap-2">
      <Badge variant="secondary" title={t("clients.joinKey.setHint", { source: jc.user_source, claim: jc.claim })}>
        {jc.join_column}
      </Badge>
      {withChanger && jc.changed_by ? (
        <span className="text-xs text-muted-foreground" title={`${jc.changed_by} · ${jc.changed_at ?? ""}`}>
          {t("clients.joinKey.by", { who: jc.changed_by })}
        </span>
      ) : null}
    </span>
  );
}

export function JoinKeyCell({ client }: { client: Client }) {
  const { t } = useTranslation();
  return (
    <Link to={`/clients/${encodeURIComponent(client.clientId)}#join-key`} className="group inline-flex items-center gap-1.5 hover:underline">
      <JoinKeyBadge client={client} />
      <Pencil aria-hidden className="h-3.5 w-3.5 shrink-0 text-muted-foreground group-hover:text-foreground" />
      <span className="sr-only">{t("clients.joinKey.change", { clientId: client.clientId })}</span>
    </Link>
  );
}

/** The 2FA column: what a login to this client does now (realm mode + client setting). */
export function TwoFactorCell({ clientId, status }: { clientId: string; status: MfaStatus }) {
  const { t } = useTranslation();
  const label = t(status.labelKey);
  const badge =
    status.kind === "required" ? (
      <Badge variant="secondary">{label}</Badge>
    ) : status.kind === "bypassed" ? (
      <Badge variant="destructive">{label}</Badge>
    ) : status.kind === "off" || status.kind === "not_configured" || status.kind === "not_required" ? (
      <Badge variant="outline">{label}</Badge>
    ) : (
      <span className="text-xs text-muted-foreground">{label}</span>
    );
  return (
    <div className="flex flex-col items-start gap-0.5" data-testid={`mfa-${clientId}`} data-kind={status.kind}>
      <Hint content={t(status.detailKey, status.params)}>
        <Link to={`/clients/${encodeURIComponent(clientId)}#mfa`} className="group inline-flex items-center gap-1.5 hover:underline">
          {badge}
          <Pencil aria-hidden className="h-3.5 w-3.5 shrink-0 text-muted-foreground group-hover:text-foreground" />
          <span className="sr-only">{t("clients.mfa.settingFor", { clientId })}</span>
        </Link>
      </Hint>
      {status.passwordGrant && (status.kind === "required" || status.kind === "bypassed") ? (
        <span className="text-[10px] text-muted-foreground" title={t("clients.mfa.passwordGrantHint")}>
          {t("clients.mfa.passwordGrantOn")}
        </span>
      ) : null}
    </div>
  );
}
