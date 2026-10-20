/**
 * Client secret: hidden by default, revealed on request (never cached), and
 * rotated after a confirmation. Public clients have no secret.
 */
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { CopyButton } from "@/components/common/CopyButton";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { clientsApi } from "../api";

export function SecretCard({ clientId, publicClient }: { clientId: string; publicClient: boolean }) {
  const { t } = useTranslation();
  const confirm = useConfirm();
  const [secret, setSecret] = useState<string | null>(null);

  const reveal = useMutation({
    mutationFn: () => clientsApi.getSecret(clientId),
    onSuccess: (r) => setSecret(r.client_secret),
    onError: (e) => setSecret(t("clients.secret.fetchFailed", { error: errorMessage(e) })),
  });
  const rotate = useMutation({
    mutationFn: () => clientsApi.regenerateSecret(clientId),
    onSuccess: (r) => {
      setSecret(r.client_secret ?? null);
      notify.success(t("clients.secret.rotated"));
      if (!r.client_secret) reveal.mutate();
    },
    onError: (e) => notify.error(e),
  });

  if (publicClient) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("clients.secret.title")}</CardTitle>
          <CardDescription>{t("clients.secret.publicClient")}</CardDescription>
        </CardHeader>
      </Card>
    );
  }

  return (
    <Card id="secret">
      <CardHeader>
        <CardTitle>{t("clients.secret.title")}</CardTitle>
        <CardDescription>{t("clients.secret.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {secret ? (
          <div className="flex items-center gap-2">
            <div className="flex-1 break-all rounded-md border bg-muted/30 px-3 py-2 font-mono text-xs" data-testid="client-secret">
              {secret}
            </div>
            <CopyButton value={secret} />
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{t("clients.secret.hidden")}</p>
        )}
      </CardContent>
      <CardFooter className="gap-2">
        {!secret ? (
          <Button variant="outline" onClick={() => reveal.mutate()} disabled={reveal.isPending}>
            {t("clients.secret.reveal")}
          </Button>
        ) : (
          <Button variant="ghost" onClick={() => setSecret(null)}>
            {t("clients.secret.hide")}
          </Button>
        )}
        <Button
          variant="destructive"
          disabled={rotate.isPending}
          onClick={async () => {
            if (await confirm({ title: t("clients.secret.rotateTitle"), description: t("clients.secret.rotateConfirm", { clientId }), destructive: true, confirmLabel: t("clients.secret.rotate") })) {
              rotate.mutate();
            }
          }}
        >
          {t("clients.secret.rotate")}
        </Button>
      </CardFooter>
    </Card>
  );
}
