/**
 * /institution/access/revoke — withdraw application access, client-first or
 * user-first. Both end in the SERVER's preview (`preview: true`) before the
 * real call. Not account deletion: accounts, membership and roles are
 * untouched. Gates are cosmetic; the wrapper asserts manage-clients.
 */
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { RevokeByApp } from "../access/components/RevokeByApp";
import { RevokeByUser } from "../access/components/RevokeByUser";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";

export function Component() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [params, setParams] = useSearchParams();
  const flow = params.get("flow") === "app" || params.get("client") ? "app" : "user";

  const missing = !inst.can("view-members") ? "view-members" : !inst.can("view-clients") ? "view-clients" : !inst.can("manage-clients") ? "manage-clients" : null;

  return (
    <div className="space-y-6">
      <PageHeader title={t("instAccess.revoke.title")} description={t("instAccess.revoke.description", { institution: inst.ctx.institution })} />
      {missing ? (
        <CapabilityGate capability={missing}>{null}</CapabilityGate>
      ) : (
        <Tabs
          value={flow}
          onValueChange={(v) => {
            const next = new URLSearchParams(inst.qs.replace(/^\?/, ""));
            if (v === "app") next.set("flow", "app");
            setParams(next);
          }}
        >
          <TabsList>
            <TabsTrigger value="user">{t("instAccess.revoke.byUser")}</TabsTrigger>
            <TabsTrigger value="app">{t("instAccess.revoke.byApp")}</TabsTrigger>
          </TabsList>
          <TabsContent value="user">
            <RevokeByUser />
          </TabsContent>
          <TabsContent value="app">
            <RevokeByApp initialClient={params.get("client") ?? ""} />
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
}
