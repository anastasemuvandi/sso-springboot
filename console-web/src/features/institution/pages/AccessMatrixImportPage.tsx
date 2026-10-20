/**
 * /institution/access/matrix/import — grant from an edited export.
 * Gated like a grant in the matrix (view-members + manage-clients); the
 * wrapper asserts the same on both preview and confirm.
 */
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { MatrixImport } from "../access/components/MatrixImport";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";

export function Component() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [params] = useSearchParams();

  const missing = !inst.can("view-members") ? "view-members" : !inst.can("manage-clients") ? "manage-clients" : null;
  if (missing) return <CapabilityGate capability={missing}>{null}</CapabilityGate>;

  // Back to the matrix with the same applications open, when we came from one.
  const back = new URLSearchParams(inst.qs.replace(/^\?/, ""));
  for (const c of params.getAll("client")) back.append("client", c);
  const matrixHref = `/institution/access/matrix${back.toString() ? `?${back}` : ""}`;

  return (
    <div className="space-y-6">
      <PageHeader title={t("instAccess.import.title")} description={t("instAccess.import.description", { institution: inst.ctx.institution })} />
      <MatrixImport matrixHref={matrixHref} />
    </div>
  );
}
