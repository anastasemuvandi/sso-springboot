/** Flow B — start from people: pick users → what they hold → apps or ALL → server preview → confirm. */
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useInstitution } from "../../context";
import { clientsForUsersQuery, membersQuery } from "../api";
import { MAX_REVOKE_USERS, revocableSelection, userFirstBody } from "../state/revokePreview";
import type { RevokeRequestBody } from "../types";
import { RevokeConfirm } from "./RevokeConfirm";

type Step = { kind: "users" } | { kind: "clients" } | { kind: "confirm"; preview: RevokeRequestBody; allClients: boolean; clients: string[] };

export function RevokeByUser() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [step, setStep] = useState<Step>({ kind: "users" });
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [users, setUsers] = useState<Set<string>>(new Set());
  const [clients, setClients] = useState<Set<string>>(new Set());

  useEffect(() => {
    const h = setTimeout(() => setQ(search.trim()), 300);
    return () => clearTimeout(h);
  }, [search]);

  const members = useQuery({ ...membersQuery(inst.scope, q), enabled: step.kind === "users" });
  const userIds = [...users];
  const held = useQuery({ ...clientsForUsersQuery(inst.scope, userIds), enabled: step.kind !== "users" && userIds.length > 0 });

  const toggle = (set: Set<string>, id: string) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  };

  if (step.kind === "users") {
    const tooMany = users.size > MAX_REVOKE_USERS;
    return (
      <div className="space-y-3">
        <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("instAccess.revoke.searchStaff")} aria-label={t("instAccess.revoke.searchStaff")} className="max-w-md" />
        {members.isPending ? (
          <Loading />
        ) : members.isError ? (
          <ErrorState error={members.error} onRetry={() => void members.refetch()} />
        ) : (
          <div className="rounded-xl border bg-card shadow-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10" />
                  <TableHead>{t("instAccess.revoke.colUser")}</TableHead>
                  <TableHead>{t("instAccess.revoke.colEmail")}</TableHead>
                  <TableHead>{t("instAccess.revoke.colStatus")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {members.data.map((m) => (
                  <TableRow key={m.id}>
                    <TableCell>
                      <input type="checkbox" checked={users.has(m.id)} onChange={() => setUsers((s) => toggle(s, m.id))} aria-label={m.username} className="h-4 w-4 rounded border-input accent-primary" />
                    </TableCell>
                    <TableCell className="text-sm font-medium">
                      {m.username}
                      {m.firstName || m.lastName ? <span className="ml-2 font-normal text-muted-foreground">{[m.firstName, m.lastName].filter(Boolean).join(" ")}</span> : null}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">{m.email ?? "—"}</TableCell>
                    <TableCell>
                      {m.enabled ? (
                        <span className="text-xs text-muted-foreground">{t("instAccess.revoke.active")}</span>
                      ) : (
                        <Badge variant="outline" className="opacity-70">
                          {t("instAccess.revoke.disabled")}
                        </Badge>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
                {members.data.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                      {t("instAccess.revoke.noStaff")}
                    </TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </div>
        )}
        <div className="flex items-center justify-between gap-3">
          <p className={tooMany ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
            {tooMany ? t("instAccess.revoke.tooMany", { max: MAX_REVOKE_USERS }) : t("instAccess.revoke.pickUsersHint")}
          </p>
          <Button variant="outline" disabled={users.size === 0 || tooMany} onClick={() => setStep({ kind: "clients" })}>
            {t("instAccess.revoke.continue")}
          </Button>
        </div>
      </div>
    );
  }

  const usernames = held.data?.usernames ?? {};
  const selectedLine = (
    <div className="rounded-lg border bg-muted/20 px-4 py-3 text-sm">
      <p className="font-medium">{t("instAccess.revoke.selectedUsers", { count: userIds.length })}</p>
      <p className="mt-0.5 text-muted-foreground">{userIds.map((id) => usernames[id] ?? id).join(", ")}</p>
      {held.data ? <p className="mt-1 text-xs text-muted-foreground">{t("instAccess.revoke.holdPairs", { count: held.data.total_pairs })}</p> : null}
      <button
        type="button"
        className="mt-1 text-xs underline"
        onClick={() => {
          setClients(new Set());
          setStep({ kind: "users" });
        }}
      >
        {t("instAccess.revoke.changeSelection")}
      </button>
    </div>
  );

  if (held.isPending) return <Loading />;
  if (held.isError) return <ErrorState error={held.error} onRetry={() => void held.refetch()} />;
  const heldClients = held.data.clients;

  const review = (allClients: boolean) => {
    const chosen = revocableSelection([...clients], heldClients);
    const body = userFirstBody({ userIds, clientIds: chosen, allClients, preview: true });
    if (body.ok) setStep({ kind: "confirm", preview: body.body, allClients, clients: chosen });
  };

  return (
    <div className="space-y-4">
      {selectedLine}
      {heldClients.length === 0 ? (
        <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instAccess.revoke.nothingHeld")}</div>
      ) : (
        <>
          <div className="rounded-xl border bg-card shadow-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10" />
                  <TableHead>{t("instAccess.revoke.colApp")}</TableHead>
                  <TableHead>{t("instAccess.revoke.colName")}</TableHead>
                  <TableHead>{t("instAccess.revoke.colOfSelected")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {heldClients.map((row) => (
                  <TableRow key={row.client_id}>
                    <TableCell>
                      <input
                        type="checkbox"
                        checked={clients.has(row.client_id)}
                        disabled={!row.revocable || step.kind === "confirm"}
                        onChange={() => setClients((s) => toggle(s, row.client_id))}
                        aria-label={row.client_id}
                        className="h-4 w-4 rounded border-input accent-primary"
                      />
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {row.client_id}
                      {row.cross_cutting ? (
                        <Badge variant="outline" className="ml-2">
                          {t("instAccess.revoke.crossCutting")}
                        </Badge>
                      ) : null}
                    </TableCell>
                    <TableCell className="text-sm">{row.name ?? "—"}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {t("instAccess.revoke.holders", { holders: row.holders, count: userIds.length })}
                      {!row.revocable ? t("instAccess.revoke.notYours") : ""}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          {step.kind !== "confirm" ? (
            <>
              <div className="flex items-center justify-between gap-3">
                <p className="text-xs text-muted-foreground">{t("instAccess.revoke.pickAppsHint")}</p>
                <Button variant="outline" disabled={revocableSelection([...clients], heldClients).length === 0} onClick={() => review(false)}>
                  {t("instAccess.revoke.review")}
                </Button>
              </div>
              <div className="rounded-lg border border-destructive/40 bg-destructive/5 px-4 py-3">
                <p className="text-sm font-medium text-destructive">{t("instAccess.revoke.allTitle")}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{t("instAccess.revoke.allBody", { count: held.data.total_pairs, institution: inst.ctx.institution })}</p>
                <Button variant="ghost" size="sm" className="mt-2 text-destructive" onClick={() => review(true)}>
                  {t("instAccess.revoke.reviewAll")}
                </Button>
              </div>
            </>
          ) : null}
        </>
      )}
      {step.kind === "confirm" ? (
        <RevokeConfirm
          preview={step.preview}
          allClients={step.allClients}
          real={(phrase) => {
            const b = userFirstBody({ userIds, clientIds: step.clients, allClients: step.allClients, preview: false, typedPhrase: phrase });
            return b.ok ? b.body : null;
          }}
          onCancel={() => setStep({ kind: "clients" })}
          onDone={() => {
            setUsers(new Set());
            setClients(new Set());
            setStep({ kind: "users" });
          }}
        />
      ) : null}
    </div>
  );
}
