import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ApiError } from "@/api/errors";
import { PageHeader } from "@/components/common/PageHeader";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { ErrorState } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { useInstitution } from "../../context";
import type { Member } from "../../types";
import { staffApi, staffKeys } from "../api";

/**
 * A 403 on a member read is the per-object guard (the id belongs to another
 * institution). Show the wrapper's wording rather than inventing our own; a
 * 404 is "no such user"; anything else is a plain error.
 */
export function MemberReadError({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
    return (
      <div className="space-y-6">
        <PageHeader title={error.status === 403 ? t("instStaff.notYours") : t("instStaff.notFound")} />
        <div className="rounded-xl border border-destructive/40 bg-card p-6 shadow-card" role="alert">
          <p className="text-sm text-muted-foreground">{error.message}</p>
          <Button asChild variant="outline" size="sm" className="mt-4">
            <Link to={inst.path("/institution/staff")}>{t("instStaff.backToStaff")}</Link>
          </Button>
        </div>
      </div>
    );
  }
  return <ErrorState error={error} onRetry={onRetry} />;
}

/**
 * Deactivate = disable + terminate every session NOW (the dedicated endpoint;
 * there is no hard delete in this console). Confirmed first.
 */
export function useDeactivate() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const m = useMutation({
    mutationFn: (member: Member) => staffApi.deactivate(inst.scope, member.id),
    onSuccess: (_d, member) => {
      notify.success(t("instStaff.deactivated", { username: member.username }));
      void qc.invalidateQueries({ queryKey: staffKeys.all(inst.scope) });
    },
    onError: (e) => notify.error(e),
  });
  return {
    pending: m.isPending,
    run: async (member: Member) => {
      const ok = await confirm({
        title: t("instStaff.deactivateTitle", { username: member.username }),
        description: t("instStaff.deactivateBody"),
        confirmLabel: t("instStaff.deactivate"),
        destructive: true,
      });
      if (ok) m.mutate(member);
    },
  };
}
