/**
 * Who is exempt from TOTP, and why. Granting needs a justification
 * (15..500 characters, MfaPolicy.validateReason) — the wrapper's
 * POST /realm/mfa/exemptions is the only path that assigns `mfa-exempt`.
 */
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslation } from "react-i18next";

import { api, unwrapAs } from "@/api/client";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { Field } from "@/components/common/Field";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { UserListResponse } from "@/features/users/types";
import { formatWhen } from "@/lib/utils";
import { exemptionsQuery, grantExemption, revokeExemption, settingsKeys } from "../api";
import { MAX_REASON_LENGTH, MIN_REASON_LENGTH, validateExemptionReason } from "../state/mfaMode";

export function ExemptionsPanel({ canChange }: { canChange: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const list = useQuery(exemptionsQuery());

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: settingsKeys.exemptions() });
    void qc.invalidateQueries({ queryKey: settingsKeys.realmMfa() });
  };

  const revoke = useMutation({
    mutationFn: revokeExemption,
    onSuccess: () => {
      notify.success(t("settings.exemptions.revoked"));
      invalidate();
    },
    onError: (e) => notify.error(e),
  });

  return (
    <div className="space-y-3" data-testid="mfa-exemptions">
      <h3 className="text-sm font-semibold">{t("settings.exemptions.title")}</h3>
      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <ErrorState error={list.error} />
      ) : list.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("settings.exemptions.none")}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("common.user")}</TableHead>
              <TableHead>{t("settings.exemptions.reason")}</TableHead>
              <TableHead>{t("settings.exemptions.grantedBy")}</TableHead>
              {canChange ? <TableHead /> : null}
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.data.map((x) => (
              <TableRow key={x.user_id}>
                <TableCell className="font-mono text-xs">{x.username ?? x.user_id}</TableCell>
                <TableCell className="max-w-md text-xs">{x.reason ?? "—"}</TableCell>
                <TableCell className="text-xs">
                  {x.granted_by ?? "—"}
                  {x.granted_at ? <div className="text-muted-foreground">{formatWhen(x.granted_at)}</div> : null}
                </TableCell>
                {canChange ? (
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant="ghost"
                      className="text-destructive"
                      disabled={revoke.isPending}
                      onClick={async () => {
                        if (
                          await confirm({
                            title: t("settings.exemptions.revokeTitle", { user: x.username ?? x.user_id }),
                            description: t("settings.exemptions.revokeBody"),
                            destructive: true,
                            confirmLabel: t("settings.exemptions.revoke"),
                          })
                        )
                          revoke.mutate(x.user_id);
                      }}
                    >
                      {t("settings.exemptions.revoke")}
                    </Button>
                  </TableCell>
                ) : null}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {canChange ? <GrantForm onGranted={invalidate} /> : null}
    </div>
  );
}

function GrantForm({ onGranted }: { onGranted: () => void }) {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [term, setTerm] = useState("");
  const [picked, setPicked] = useState<{ id: string; username: string } | null>(null);

  const users = useQuery({
    queryKey: ["users", "list", { search: term, page_size: 10, purpose: "mfa-exemption" }],
    queryFn: () => unwrapAs<UserListResponse>(api.GET("/users/", { params: { query: { search: term, page_size: 10 } } })),
    enabled: term.length > 0,
  });

  const schema = z.object({
    reason: z.string().superRefine((v, ctx) => {
      const r = validateExemptionReason(v);
      if (!r.ok) ctx.addIssue({ code: z.ZodIssueCode.custom, message: t(`settings.exemptions.${r.error === "too_short" ? "reasonTooShort" : "reasonTooLong"}`, { min: MIN_REASON_LENGTH, max: MAX_REASON_LENGTH }) });
    }),
  });
  const form = useForm<{ reason: string }>({ resolver: zodResolver(schema), defaultValues: { reason: "" } });

  const grant = useMutation({
    mutationFn: (reason: string) => grantExemption(picked!.id, reason),
    onSuccess: () => {
      notify.success(t("settings.exemptions.granted", { user: picked?.username ?? "" }));
      setPicked(null);
      form.reset();
      onGranted();
    },
    onError: (e) => notify.error(e),
  });

  return (
    <form
      className="space-y-3 rounded-md border p-3"
      onSubmit={form.handleSubmit((v) => {
        const r = validateExemptionReason(v.reason);
        if (picked && r.ok) grant.mutate(r.value);
      })}
      aria-label={t("settings.exemptions.grantTitle")}
    >
      <div className="text-sm font-medium">{t("settings.exemptions.grantTitle")}</div>
      {picked ? (
        <div className="flex items-center gap-2 text-sm">
          <span className="font-mono">{picked.username}</span>
          <Button size="sm" variant="ghost" onClick={() => setPicked(null)}>
            {t("settings.exemptions.change")}
          </Button>
        </div>
      ) : (
        <div className="space-y-2">
          <div className="flex gap-2">
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t("settings.exemptions.searchUser")}
              aria-label={t("settings.exemptions.searchUser")}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  setTerm(search.trim());
                }
              }}
            />
            <Button variant="secondary" onClick={() => setTerm(search.trim())}>
              {t("common.search")}
            </Button>
          </div>
          {users.isFetching ? <Loading /> : null}
          {users.isError ? <ErrorState error={users.error} /> : null}
          {users.data ? (
            users.data.users.length === 0 ? (
              <p className="text-xs text-muted-foreground">{t("settings.exemptions.noUsers")}</p>
            ) : (
              <ul className="max-h-48 divide-y overflow-y-auto rounded-md border">
                {users.data.users.map((u) => (
                  <li key={u.id}>
                    <button type="button" className="w-full px-3 py-1.5 text-left text-sm hover:bg-muted" onClick={() => setPicked({ id: u.id, username: u.username })}>
                      <span className="font-mono">{u.username}</span>
                      {u.email ? <span className="ml-2 text-xs text-muted-foreground">{u.email}</span> : null}
                    </button>
                  </li>
                ))}
              </ul>
            )
          ) : null}
        </div>
      )}
      <Field label={t("settings.exemptions.reason")} htmlFor="exemption-reason" help={t("settings.exemptions.reasonHelp", { min: MIN_REASON_LENGTH, max: MAX_REASON_LENGTH })} error={form.formState.errors.reason?.message}>
        <Textarea id="exemption-reason" {...form.register("reason")} />
      </Field>
      <Button type="submit" disabled={!picked || grant.isPending}>
        {t("settings.exemptions.grant")}
      </Button>
    </form>
  );
}
