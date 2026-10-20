import type { ReactNode } from "react";
import { Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { useSession } from "@/auth/session";
import { useInstitutionStatus } from "@/features/institution/context";
import { INSTITUTION_NAV, PLATFORM_NAV, type NavGroup } from "./nav";
import { Sidebar } from "./Sidebar";
import { TopBar } from "./TopBar";

/** The console frame: sidebar + top bar + routed page. One per area. */
/**
 * Institution nav: hide entries the delegated tier lacks (cosmetic — the
 * endpoint answers 403 anyway) and keep `?institution=` on every link.
 */
function institutionNav(status: ReturnType<typeof useInstitutionStatus>): NavGroup[] {
  if (status.kind !== "ready") return INSTITUTION_NAV.map((g) => ({ ...g, items: g.items.filter((i) => !i.needs) }));
  const { can, qs } = status.value;
  return INSTITUTION_NAV.map((g) => ({
    ...g,
    items: g.items.filter((i) => !i.needs || can(i.needs)).map((i) => ({ ...i, to: `${i.to}${qs}` })),
  }));
}

export function ConsoleLayout({ area, content }: { area: "platform" | "institution"; content?: ReactNode }) {
  const { t } = useTranslation();
  const session = useSession();
  const instStatus = useInstitutionStatus();
  const isInstitution = area === "institution";
  const switchTo =
    isInstitution && session.platformAdmin
      ? { to: "/dashboard", label: t("shell.toPlatformConsole") }
      : !isInstitution && session.institutionAdmin
        ? { to: "/institution", label: t("shell.toInstitutionConsole") }
        : undefined;

  return (
    <div className="flex h-screen bg-background">
      <Sidebar groups={isInstitution ? institutionNav(instStatus) : PLATFORM_NAV} home={isInstitution ? "/institution" : "/dashboard"} switchTo={switchTo} />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar area={area} />
        <main className="flex-1 overflow-y-auto px-6 py-8 sm:px-10 sm:py-10" data-area={area}>
          {content ?? <Outlet />}
        </main>
      </div>
    </div>
  );
}
