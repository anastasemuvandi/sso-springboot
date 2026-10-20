/**
 * The scope chooser: who owns this client, or which institutions may use it.
 * Controlled; the pure rules live in `state/clientScopeForm.ts`.
 *
 * "All institutions" is DYNAMIC (a policy, `*`); ticking every box is a fixed
 * snapshot. A cross-cutting client with no institution is refused here and,
 * regardless, by the wrapper (400).
 */
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import type { InstitutionOption } from "../api";
import { institutionsLosingAccess, scopeRequest, toggleSelected, type ScopeFormValues, type ScopeMode } from "../state/clientScopeForm";
import type { ClientScopeImpact } from "../types";

export function ClientScopeFields({
  institutions,
  value,
  onChange,
  access = [],
  disabled = false,
}: {
  institutions: InstitutionOption[];
  value: ScopeFormValues;
  onChange: (v: ScopeFormValues) => void;
  /** Today's foothold per institution (edit page): drives the removal preview. */
  access?: ClientScopeImpact[];
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const known = useMemo(() => institutions.map((i) => i.slug), [institutions]);
  const losing = institutionsLosingAccess(value, access, known);
  const footholdOf = (slug: string) => access.find((a) => a.institution === slug);
  const request = scopeRequest(value);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return needle ? institutions.filter((i) => i.slug.includes(needle) || i.displayName.toLowerCase().includes(needle)) : institutions;
  }, [institutions, q]);

  const modes: Array<[ScopeMode, string, string]> = [
    ["institution", t("clients.scopeForm.modeInstitution"), t("clients.scopeForm.modeInstitutionHint")],
    ["cross-cutting", t("clients.scopeForm.modeCrossCutting"), t("clients.scopeForm.modeCrossCuttingHint")],
    ["platform", t("clients.scopeForm.modePlatform"), t("clients.scopeForm.modePlatformHint")],
  ];

  const option = (checked: boolean) =>
    cn(
      "flex gap-2 rounded-md border px-3 py-2",
      checked ? "border-primary bg-background" : "border-transparent",
      disabled ? "cursor-default opacity-60" : "cursor-pointer hover:bg-background",
    );

  return (
    <div className="space-y-4 rounded-lg border border-border bg-muted/20 p-4" data-testid="scope-fields">
      <div className="space-y-1">
        <Label>{t("clients.scopeForm.title")}</Label>
        <p className="text-xs text-muted-foreground">{t("clients.scopeForm.explain")}</p>
      </div>

      <div className="space-y-2" role="radiogroup" aria-label={t("clients.scopeForm.title")}>
        {modes.map(([mode, label, hint]) => (
          <label key={mode} className={option(value.mode === mode)}>
            <input
              type="radio"
              name="scope_mode"
              value={mode}
              checked={value.mode === mode}
              disabled={disabled}
              onChange={() => onChange({ ...value, mode })}
              className="mt-0.5 h-4 w-4 shrink-0 accent-primary"
            />
            <span className="min-w-0">
              <span className="block text-sm font-medium">{label}</span>
              <span className="block text-xs text-muted-foreground">{hint}</span>
            </span>
          </label>
        ))}
      </div>

      {value.mode === "institution" ? (
        <div className="space-y-2">
          <Label htmlFor="owner_institution">{t("clients.scopeForm.owner")}</Label>
          <select
            id="owner_institution"
            value={value.owner}
            disabled={disabled}
            onChange={(e) => onChange({ ...value, owner: e.target.value })}
            className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm"
          >
            <option value="">{t("clients.scopeForm.selectInstitution")}</option>
            {institutions.map((i) => (
              <option key={i.slug} value={i.slug}>
                {i.displayName} ({i.slug})
              </option>
            ))}
          </select>
          <p className="text-xs text-muted-foreground">
            {t("clients.scopeForm.ownerHint")} <code className="font-mono text-[11px]">{(value.owner || "<slug>") + "-<app>"}</code>
          </p>
        </div>
      ) : null}

      {value.mode === "cross-cutting" ? (
        <div className="space-y-3">
          <div className="space-y-2">
            <label className={option(value.all)}>
              <input
                type="radio"
                name="cross_cutting_reach"
                checked={value.all}
                disabled={disabled}
                onChange={() => onChange({ ...value, all: true })}
                className="mt-0.5 h-4 w-4 shrink-0 accent-primary"
              />
              <span>
                <span className="block text-sm font-medium">{t("clients.scope.allInstitutions")}</span>
                <span className="block text-xs text-muted-foreground">{t("clients.scopeForm.allHint")}</span>
              </span>
            </label>
            <label className={option(!value.all)}>
              <input
                type="radio"
                name="cross_cutting_reach"
                checked={!value.all}
                disabled={disabled}
                onChange={() => onChange({ ...value, all: false })}
                className="mt-0.5 h-4 w-4 shrink-0 accent-primary"
              />
              <span>
                <span className="block text-sm font-medium">{t("clients.scopeForm.selected")}</span>
                <span className="block text-xs text-muted-foreground">{t("clients.scopeForm.selectedHint")}</span>
              </span>
            </label>
          </div>

          {!value.all ? (
            <div className="space-y-2">
              <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("clients.scopeForm.search")} aria-label={t("clients.scopeForm.search")} disabled={disabled} />
              <div className="max-h-64 divide-y overflow-y-auto rounded-md border bg-background">
                {filtered.length === 0 ? (
                  <p className="px-3 py-2 text-sm text-muted-foreground">{t("clients.scopeForm.noMatch", { q })}</p>
                ) : (
                  filtered.map((i) => {
                    const f = footholdOf(i.slug);
                    return (
                      <label key={i.slug} className="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 hover:bg-muted/50">
                        <span className="flex min-w-0 items-center gap-2">
                          <input
                            type="checkbox"
                            className="h-4 w-4 shrink-0 accent-primary"
                            checked={value.selected.includes(i.slug)}
                            disabled={disabled}
                            onChange={() => onChange(toggleSelected(value, i.slug))}
                            aria-label={i.slug}
                          />
                          <span className="truncate text-sm">
                            {i.displayName} <span className="font-mono text-xs text-muted-foreground">{i.slug}</span>
                          </span>
                        </span>
                        {f && (f.group_assigned || f.users > 0) ? (
                          <span className="shrink-0 text-xs text-muted-foreground">{t("clients.scopeForm.onClient", { count: f.users })}</span>
                        ) : null}
                      </label>
                    );
                  })
                )}
              </div>
              <p className="text-xs text-muted-foreground">
                {value.selected.length > 0 ? t("clients.scopeForm.nSelected", { count: value.selected.length }) : t("clients.scopeForm.tickOne")}
              </p>
            </div>
          ) : null}
        </div>
      ) : null}

      {!request.ok ? (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-destructive">
          {t(`clients.scopeForm.error.${request.error}`)}
        </p>
      ) : null}

      {losing.length > 0 ? (
        <div className="rounded-md border border-amber-500/50 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:bg-amber-950/30 dark:text-amber-200" data-testid="scope-losing">
          <p className="font-medium">{t("clients.scopeForm.losingTitle")}</p>
          <ul className="mt-1 list-disc pl-4">
            {losing.map((l) => (
              <li key={l.institution}>
                <span className="font-mono">{l.institution}</span> — {t("clients.scopeForm.impact", { count: l.users })}
                {l.group_assigned ? ` · ${t("clients.scopeForm.groupAssigned")}` : ""}
              </li>
            ))}
          </ul>
          <p className="mt-1">{t("clients.scopeForm.losingHint")}</p>
        </div>
      ) : null}
    </div>
  );
}
