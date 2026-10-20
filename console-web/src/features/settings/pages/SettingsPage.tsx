/**
 * /settings — realm configuration: TOTP mode + exemptions, the editable realm
 * subset (RealmConfig), branding + logo, SMTP + test, themes, and a read-only
 * view of the token/event settings managed as code.
 *
 * Edit controls are shown to super admins only. That is presentation: the
 * wrapper refuses every write to anyone else whatever this page renders.
 */
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { useSession } from "@/auth/session";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { brandingQuery, emailConfigQuery, realmConfigQuery, themesQuery } from "../api";
import { BrandingForm } from "../components/BrandingForm";
import { ConfigRow } from "../components/ConfigRow";
import { MfaCard } from "../components/MfaCard";
import { PageSection } from "../components/PageSection";
import { RealmConfigFormCard } from "../components/RealmConfigForm";
import { SmtpForm } from "../components/SmtpForm";
import { canChangeMfa } from "../state/mfaMode";

/** Managed as code (Terraform / configs/realms) — shown, not edited. */
const READ_ONLY_GROUPS: Array<{ key: string; keys: string[] }> = [
  {
    key: "tokens",
    keys: ["sslRequired", "passwordPolicy", "accessTokenLifespan", "ssoSessionIdleTimeout", "ssoSessionMaxLifespan", "offlineSessionIdleTimeout", "offlineSessionMaxLifespan", "revokeRefreshToken", "refreshTokenMaxReuse"],
  },
  { key: "events", keys: ["eventsEnabled", "eventsExpiration", "eventsListeners", "enabledEventTypes", "adminEventsEnabled", "adminEventsDetailsEnabled"] },
  { key: "webauthn", keys: ["webAuthnPolicyRpEntityName", "webAuthnPolicySignatureAlgorithms", "webAuthnPolicyUserVerificationRequirement"] },
];

export function Component() {
  const { t } = useTranslation();
  const session = useSession();
  const canEdit = canChangeMfa(session.roles);
  const config = useQuery(realmConfigQuery());
  const themes = useQuery(themesQuery());
  const branding = useQuery(brandingQuery());
  const email = useQuery(emailConfigQuery());

  return (
    <div className="max-w-5xl space-y-6">
      <PageHeader title={t("settings.title")} description={t("settings.description")} />

      <MfaCard canChange={canEdit} />

      <PageSection id="realm" title={t("settings.realm.title")} description={t("settings.realm.description")}>
        {config.isPending ? (
          <Loading />
        ) : config.isError ? (
          <ErrorState error={config.error} onRetry={() => void config.refetch()} />
        ) : (
          <RealmConfigFormCard key={JSON.stringify(config.data)} realm={config.data} themes={themes.data} canEdit={canEdit} />
        )}
      </PageSection>

      <PageSection id="branding" title={t("settings.branding.title")} description={t("settings.branding.description")}>
        {branding.isPending ? (
          <Loading />
        ) : branding.isError ? (
          <ErrorState error={branding.error} onRetry={() => void branding.refetch()} />
        ) : (
          <BrandingForm key={JSON.stringify(branding.data)} branding={branding.data} canEdit={canEdit} />
        )}
      </PageSection>

      <PageSection id="smtp" title={t("settings.smtp.title")} description={t("settings.smtp.description")}>
        {email.isPending ? (
          <Loading />
        ) : email.isError ? (
          <ErrorState error={email.error} onRetry={() => void email.refetch()} />
        ) : (
          <SmtpForm key={JSON.stringify(email.data)} config={email.data} canEdit={canEdit} />
        )}
      </PageSection>

      <PageSection id="themes" title={t("settings.themes.title")} description={t("settings.themes.description")}>
        {themes.isPending ? (
          <Loading />
        ) : themes.isError ? (
          <ErrorState error={themes.error} />
        ) : (
          <div className="text-sm">
            {(["login", "account", "admin", "email"] as const).map((k) => (
              <ConfigRow key={k} label={k} value={themes.data[k] ?? []} />
            ))}
          </div>
        )}
      </PageSection>

      {config.data
        ? READ_ONLY_GROUPS.map((g) => (
            <PageSection key={g.key} title={t(`settings.readOnly.${g.key}`)} description={t("settings.readOnly.description")}>
              <div className="text-sm">
                {g.keys.map((k) => (
                  <ConfigRow key={k} label={k} value={config.data[k]} />
                ))}
              </div>
            </PageSection>
          ))
        : null}
    </div>
  );
}
