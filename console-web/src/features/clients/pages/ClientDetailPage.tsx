/**
 * /clients/:id — one client: redirect URIs, institution scope (409 confirm),
 * access (groups / users / denied users), MFA, join key (409 confirm +
 * readiness), post-logout URIs, secret, delete. `:id` is the clientId.
 *
 * Each card reads independently, so one failing read (e.g. the service
 * account lacking authz rights on a control-plane client) never blanks the
 * page. Everything is enforced by the wrapper.
 */
import { useEffect } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { useConfirm } from "@/components/common/ConfirmDialog";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { clientKeys, clientQuery, clientsApi, postLogoutUris } from "../api";
import { AccessCard } from "../components/AccessCard";
import { JoinColumnEditor } from "../components/JoinColumnEditor";
import { MfaCard } from "../components/MfaCard";
import { ScopeEditor } from "../components/ScopeEditor";
import { SecretCard } from "../components/SecretCard";
import { UriListCard } from "../components/UriListCard";

export function ClientDetailPage() {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const clientId = decodeURIComponent(id);
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const q = useQuery(clientQuery(clientId));

  // Deep links from the list (#mfa, #join-key, #access).
  useEffect(() => {
    if (!q.data || !location.hash) return;
    document.getElementById(location.hash.slice(1))?.scrollIntoView?.({ block: "start" });
  }, [q.data, location.hash]);

  const del = useMutation({
    mutationFn: () => clientsApi.remove(clientId),
    onSuccess: () => {
      notify.success(t("clients.deleted", { clientId }));
      void qc.invalidateQueries({ queryKey: clientKeys.all });
      navigate("/clients");
    },
    onError: (e) => notify.error(e),
  });

  if (q.isPending) return <Loading />;
  if (q.isError) {
    return (
      <div className="mx-auto max-w-2xl space-y-4">
        <ErrorState error={q.error} title={t("clients.detail.notFound")} />
        <Button asChild variant="outline">
          <Link to="/clients">{t("clients.detail.backToClients")}</Link>
        </Button>
      </div>
    );
  }
  const client = q.data;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <UriListCard
        id="redirect_uris"
        clientId={clientId}
        title={client.clientId}
        description={
          <>
            {client.name ?? t("clients.detail.noDisplayName")} · <code>{client.protocol ?? "openid-connect"}</code> ·{" "}
            {client.enabled ? t("clients.detail.enabledWord") : t("clients.detail.disabledWord")}
          </>
        }
        label={t("clients.detail.redirectUris")}
        help={t("clients.detail.redirectUrisHelp")}
        initial={client.redirectUris ?? []}
        save={(uris) => clientsApi.setRedirectUris(clientId, uris)}
        saveLabel={t("clients.detail.saveRedirectUris")}
        footerStart={
          <Button asChild variant="ghost">
            <Link to="/clients">{t("common.back")}</Link>
          </Button>
        }
      />
      <details className="text-xs">
        <summary className="cursor-pointer text-muted-foreground hover:text-foreground">{t("clients.detail.raw")}</summary>
        <pre className="mt-2 overflow-auto rounded-md bg-muted p-3 font-mono">{JSON.stringify(client, null, 2)}</pre>
      </details>

      <ScopeEditor clientId={clientId} />
      <AccessCard clientId={clientId} />
      <MfaCard clientId={clientId} />
      <JoinColumnEditor clientId={clientId} />

      <UriListCard
        id="post_logout_redirect_uris"
        clientId={clientId}
        title={t("clients.detail.postLogoutTitle")}
        description={t("clients.detail.postLogoutDescription")}
        label={t("clients.detail.postLogoutLabel")}
        help={t("clients.detail.postLogoutHelp")}
        initial={postLogoutUris(client)}
        save={(uris) => clientsApi.setPostLogoutUris(clientId, uris)}
        saveLabel={t("clients.detail.savePostLogout")}
      />

      <SecretCard clientId={clientId} publicClient={Boolean(client.publicClient)} />

      <Card className="border-destructive/40">
        <CardHeader>
          <CardTitle className="text-destructive">{t("clients.detail.dangerZone")}</CardTitle>
          <CardDescription>{t("clients.detail.dangerExplain")}</CardDescription>
        </CardHeader>
        <CardContent />
        <CardFooter>
          <Button
            variant="destructive"
            disabled={del.isPending}
            onClick={async () => {
              if (await confirm({ title: t("clients.deleteTitle"), description: t("clients.deleteConfirm", { clientId }), destructive: true, confirmLabel: t("common.delete") })) {
                del.mutate();
              }
            }}
          >
            {t("clients.detail.deleteClient")}
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}

export const Component = ClientDetailPage;
