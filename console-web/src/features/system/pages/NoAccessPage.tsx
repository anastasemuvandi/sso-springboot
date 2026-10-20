import { useRef } from "react";
import { useTranslation } from "react-i18next";

import { useSession } from "@/auth/session";
import { Button } from "@/components/ui/button";
import { SignOutForm } from "@/components/shell/SignOutForm";

/**
 * Signed in, but neither a platform-admin role nor an institution grant —
 * signing in again will not help; an administrator has to grant one.
 */
export function Component() {
  const { t } = useTranslation();
  const session = useSession();
  const form = useRef<HTMLFormElement>(null);
  return (
    <div className="grid min-h-screen place-items-center bg-page px-6 py-16">
      <div className="w-full max-w-xl rounded-xl border border-border bg-card p-6 shadow-card">
        <h1 className="text-lg font-semibold tracking-tight">{t("noAccess.title")}</h1>
        <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
          {t("noAccess.body", { username: session.username ?? "?" })}
        </p>
        <div className="mt-4 rounded-md bg-muted/60 px-3 py-2">
          <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t("noAccess.yourRoles")}</div>
          <div className="mt-1 break-words font-mono text-xs">{session.roles?.length ? session.roles.join(", ") : t("common.none")}</div>
        </div>
        <Button className="mt-4" variant="outline" size="sm" onClick={() => form.current?.submit()}>
          {t("shell.signOut")}
        </Button>
        <SignOutForm ref={form} />
      </div>
    </div>
  );
}
