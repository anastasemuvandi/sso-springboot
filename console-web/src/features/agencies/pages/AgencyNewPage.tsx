/** /agencies/new — creates a Keycloak group at the realm root. */
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { agencyKeys, createAgency } from "../api";

const schema = z.object({
  name: z.string().trim().min(1, "agencies.form.nameRequired"),
  description: z.string().trim().optional(),
});
type FormValues = z.infer<typeof schema>;

export function Component() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: "", description: "" } });

  const create = useMutation({
    mutationFn: (v: FormValues) => createAgency({ name: v.name, description: v.description || undefined }),
    onSuccess: () => {
      notify.success(t("agencies.created"));
      void qc.invalidateQueries({ queryKey: agencyKeys.all });
      navigate("/agencies");
    },
    onError: (e) => notify.error(e),
  });

  const err = form.formState.errors;
  return (
    <div className="max-w-2xl">
      <Card>
        <CardHeader>
          <CardTitle>{t("agencies.new")}</CardTitle>
          <CardDescription>{t("agencies.form.intro")}</CardDescription>
        </CardHeader>
        <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
          <CardContent className="space-y-4">
            <Field label={t("common.name")} htmlFor="name" help={t("agencies.form.nameHelp")} error={err.name?.message ? t(err.name.message) : undefined}>
              <Input id="name" placeholder="e.g. MININFRA" {...form.register("name")} />
            </Field>
            <Field label={t("agencies.form.descriptionOptional")} htmlFor="description">
              <Input id="description" placeholder="Ministry of Infrastructure" {...form.register("description")} />
            </Field>
          </CardContent>
          <CardFooter className="flex justify-between">
            <Button asChild variant="ghost">
              <Link to="/agencies">{t("common.cancel")}</Link>
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? t("common.saving") : t("agencies.form.create")}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  );
}
