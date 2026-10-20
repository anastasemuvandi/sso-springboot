/**
 * /institution/access/matrix — the employee × application access matrix.
 *
 * Two steps, the first driven by the URL (`?client=…&client=…`): pick the
 * applications, then the matrix itself. The capability gates are cosmetic;
 * the wrapper asserts view-clients/view-members/manage-clients per call.
 */
import { Link, useSearchParams } from "react-router-dom";
import { Download, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { exportMatrix } from "../access/api";
import { AppPicker } from "../access/components/AppPicker";
import { MatrixGrid } from "../access/components/MatrixGrid";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";

const HERE = "/institution/access/matrix";

/** A path keeping `?institution=` and the chosen `client` params. */
function withClients(base: string, qs: string, clients: string[]): string {
  const params = new URLSearchParams(qs.replace(/^\?/, ""));
  for (const c of clients) params.append("client", c);
  const s = params.toString();
  return s ? `${base}?${s}` : base;
}

export function Component() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [params, setParams] = useSearchParams();
  const chosen = params.getAll("client").filter(Boolean);

  const missing = !inst.can("view-members") ? "view-members" : !inst.can("view-clients") ? "view-clients" : null;
  if (missing) return <CapabilityGate capability={missing}>{null}</CapabilityGate>;

  const open = (clients: string[]) => {
    const next = new URLSearchParams(inst.qs.replace(/^\?/, ""));
    for (const c of clients) next.append("client", c);
    setParams(next);
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instAccess.matrix.title")}
        description={t("instAccess.matrix.description", { institution: inst.ctx.institution })}
        actions={
          chosen.length > 0 ? (
            <>
              <Button variant="outline" onClick={() => exportMatrix(inst.scope, chosen).catch((e) => notify.error(e, t("instAccess.matrix.exportFailed")))}>
                <Download /> {t("instAccess.matrix.export")}
              </Button>
              {inst.can("manage-clients") ? (
                <Button asChild variant="outline">
                  <Link to={withClients(`${HERE}/import`, inst.qs, chosen)}>
                    <Upload /> {t("instAccess.matrix.import")}
                  </Link>
                </Button>
              ) : null}
              <Button asChild variant="outline">
                <Link to={inst.path(HERE)}>{t("instAccess.matrix.changeApps")}</Link>
              </Button>
            </>
          ) : null
        }
      />
      {chosen.length > 0 ? (
        <MatrixGrid key={chosen.join(",")} clientIds={chosen} canManage={inst.can("manage-clients")} canManageMembers={inst.can("manage-members")} />
      ) : (
        <AppPicker initial={[]} onOpen={open} />
      )}
    </div>
  );
}
