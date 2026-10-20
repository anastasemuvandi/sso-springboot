/**
 * /clients/new — register a client (PKCE-required, implicit-flow-disabled
 * defaults are set by the wrapper). The zod schema mirrors ClientCreateRequest;
 * the scope half comes from the shared scope form state.
 */
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { clientKeys, clientsApi, institutionOptionsQuery, splitUris } from "../api";
import { ClientScopeFields } from "../components/ClientScopeFields";
import { EMPTY_SCOPE_FORM, scopeRequest, type ScopeFormValues } from "../state/clientScopeForm";

const schema = z.object({
  client_name: z.string().trim().min(1, "clients.new.errors.nameRequired"),
  redirect_uris: z.string().refine((v) => splitUris(v).length > 0, "clients.new.errors.redirectRequired"),
  post_logout_redirect_uris: z.string().optional(),
  protocol: z.enum(["openid-connect", "saml"]),
});
type FormValues = z.infer<typeof schema>;

export function ClientNewPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const institutions = useQuery(institutionOptionsQuery());
  const [scope, setScope] = useState<ScopeFormValues>(EMPTY_SCOPE_FORM);
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { client_name: "", redirect_uris: "", post_logout_redirect_uris: "", protocol: "openid-connect" },
  });

  const noInstitutions = institutions.isSuccess && institutions.data.length === 0;
  // With no active institution the client can only be platform-internal.
  const effectiveScope: ScopeFormValues = noInstitutions ? { ...EMPTY_SCOPE_FORM, mode: "platform" } : scope;
  const scopeReq = scopeRequest(effectiveScope);

  const create = useMutation({
    mutationFn: (v: FormValues) => {
      if (!scopeReq.ok) throw new Error(t(`clients.scopeForm.error.${scopeReq.error}`));
      return clientsApi.create({
        client_name: v.client_name.trim(),
        redirect_uris: splitUris(v.redirect_uris),
        post_logout_redirect_uris: v.post_logout_redirect_uris?.trim() || undefined,
        protocol: v.protocol,
        ...scopeReq.fields,
      });
    },
    onSuccess: (c) => {
      notify.success(t("clients.new.created", { clientId: c?.clientId ?? form.getValues("client_name") }));
      void qc.invalidateQueries({ queryKey: clientKeys.all });
      navigate("/clients");
    },
    onError: (e) => notify.error(e),
  });

  const err = (k?: string) => (k ? t(k) : undefined);

  return (
    <div className="mx-auto max-w-2xl">
      <Card>
        <CardHeader>
          <CardTitle>{t("clients.new.title")}</CardTitle>
          <CardDescription>{t("clients.new.description")}</CardDescription>
        </CardHeader>
        <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
          <CardContent className="space-y-4">
            <Field label={t("clients.new.clientId")} htmlFor="client_name" help={t("clients.new.clientIdHelp")} error={err(form.formState.errors.client_name?.message)}>
              <Input id="client_name" placeholder="minagri-portal" {...form.register("client_name")} />
            </Field>
            <Field label={t("clients.detail.redirectUris")} htmlFor="redirect_uris" help={t("clients.new.redirectHelp")} error={err(form.formState.errors.redirect_uris?.message)}>
              <Textarea id="redirect_uris" rows={3} placeholder={"http://localhost:9000/callback\nhttps://minagri.gov.rw/auth/callback"} {...form.register("redirect_uris")} />
            </Field>
            <Field label={t("clients.new.postLogout")} htmlFor="post_logout_redirect_uris" help={t("clients.new.postLogoutHelp")}>
              <Input id="post_logout_redirect_uris" placeholder="http://localhost:9000/" {...form.register("post_logout_redirect_uris")} />
            </Field>
            <Field label={t("clients.new.protocol")} htmlFor="protocol">
              <select id="protocol" className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm" {...form.register("protocol")}>
                <option value="openid-connect">{t("clients.new.oidc")}</option>
                <option value="saml">{t("clients.new.saml")}</option>
              </select>
            </Field>

            {institutions.isError ? (
              <p className="text-xs text-destructive">{t("clients.scopeEditor.institutionsFailed", { error: errorMessage(institutions.error) })}</p>
            ) : noInstitutions ? (
              <p className="rounded-md border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">{t("clients.new.noInstitutions")}</p>
            ) : (
              <ClientScopeFields institutions={institutions.data ?? []} value={scope} onChange={setScope} />
            )}
          </CardContent>
          <CardFooter className="flex justify-between">
            <Button asChild variant="ghost">
              <Link to="/clients">{t("common.cancel")}</Link>
            </Button>
            <Button type="submit" disabled={create.isPending || !scopeReq.ok}>
              {create.isPending ? t("common.saving") : t("clients.new.create")}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  );
}

export const Component = ClientNewPage;
