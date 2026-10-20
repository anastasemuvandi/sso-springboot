import { useTranslation } from "react-i18next";

/** Why NID and phone are on the form: they may be an application's join key. */
export function IdentifierHelp() {
  const { t } = useTranslation();
  return (
    <div className="rounded-md border border-border bg-muted/40 px-3 py-2.5 text-xs leading-relaxed text-muted-foreground">
      <p className="font-medium text-foreground">{t("instStaff.idHelpTitle")}</p>
      <p className="mt-1">{t("instStaff.idHelpBody")}</p>
    </div>
  );
}
