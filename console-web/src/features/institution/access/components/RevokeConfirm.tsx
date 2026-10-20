/**
 * Step 3 of both revoke flows: the server's preview, then the real call.
 * For "all applications" the console also asks for a typed phrase; the
 * wrapper separately requires `confirm_all_clients`.
 */
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useInstitution } from "../../context";
import { accessKeys, revokeAccess, revokePreviewQuery } from "../api";
import { canConfirm, CONFIRM_PHRASE } from "../state/revokePreview";
import type { RevokeReport, RevokeRequestBody } from "../types";
import { RevokeSummary } from "./RevokeSummary";

export function RevokeConfirm({
  preview,
  real,
  allClients,
  onCancel,
  onDone,
}: {
  /** The `preview: true` body. */
  preview: RevokeRequestBody;
  /** Builds the real body (needs the typed phrase for all clients); null = not allowed. */
  real: (typedPhrase: string) => RevokeRequestBody | null;
  allClients: boolean;
  onCancel: () => void;
  onDone: (report: RevokeReport) => void;
}) {
  const { t } = useTranslation();
  const { scope } = useInstitution();
  const qc = useQueryClient();
  const [phrase, setPhrase] = useState("");
  const q = useQuery(revokePreviewQuery(scope, preview));
  const mutation = useMutation({
    mutationFn: (body: RevokeRequestBody) => revokeAccess(scope, body),
    onSuccess: (report) => {
      notify.success(
        t("instAccess.revoke.done", {
          revocations: report.total_revocations,
          users: report.users_affected,
          skipped: report.total_skipped > 0 ? t("instAccess.revoke.doneSkipped", { count: report.total_skipped }) : "",
        }),
      );
      onDone(report);
      // Refresh holders/matrix reads — but never re-run a preview of a call that just happened.
      void qc.invalidateQueries({ queryKey: accessKeys.all(scope), predicate: (q) => q.queryKey[3] !== "revoke-preview" });
    },
    onError: (e) => notify.error(e),
  });

  return (
    <div className="rounded-xl border border-destructive/40 bg-destructive/5 p-4" data-testid="revoke-confirm">
      <h2 className="text-sm font-semibold text-destructive">{allClients ? t("instAccess.revoke.confirmAllTitle") : t("instAccess.revoke.confirmTitle")}</h2>
      <div className="mt-3">
        {q.isPending ? <Loading /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : <RevokeSummary report={q.data} />}
      </div>
      {allClients ? (
        <div className="mt-4 space-y-2">
          <Label htmlFor="revoke-phrase" className="text-destructive">
            {t("instAccess.revoke.typePhrase", { phrase: CONFIRM_PHRASE })}
          </Label>
          <Input id="revoke-phrase" value={phrase} onChange={(e) => setPhrase(e.target.value)} autoComplete="off" placeholder={CONFIRM_PHRASE} className="max-w-xs" />
          <p className="text-xs text-muted-foreground">{t("instAccess.revoke.allWarning")}</p>
        </div>
      ) : null}
      <div className="mt-4 flex items-center gap-2">
        <Button
          variant="destructive"
          size="sm"
          disabled={!canConfirm(q.data, allClients, phrase) || mutation.isPending}
          onClick={() => {
            const body = real(phrase);
            if (body) mutation.mutate(body);
          }}
        >
          {t("instAccess.revoke.remove")}
        </Button>
        <Button variant="ghost" size="sm" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
      </div>
    </div>
  );
}
