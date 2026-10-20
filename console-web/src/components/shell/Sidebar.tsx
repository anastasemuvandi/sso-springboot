import { useState } from "react";
import { Link, NavLink } from "react-router-dom";
import { ArrowLeftRight, ChevronsLeft, ChevronsRight } from "lucide-react";
import { useTranslation } from "react-i18next";

import { cn } from "@/lib/utils";
import { BrandMark } from "./BrandMark";
import type { NavGroup } from "./nav";

export function Sidebar({
  groups,
  home,
  switchTo,
}: {
  groups: NavGroup[];
  home: string;
  /** Optional link to the other console (platform admins acting for an institution). */
  switchTo?: { to: string; label: string };
}) {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState(false);

  return (
    <aside
      aria-label={t("shell.sidebar")}
      className={cn(
        "hidden shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-[width] duration-200 md:flex",
        collapsed ? "w-[64px]" : "w-[240px]",
      )}
    >
      <div className="gor-flag-stripe h-1 w-full" aria-hidden />
      <div className="flex h-14 items-center justify-between px-3">
        <Link to={home} className="flex items-center gap-2 px-1 text-sm font-semibold text-foreground">
          <BrandMark className="h-7 w-7" />
          {!collapsed && <span>{t("shell.brand")}</span>}
        </Link>
        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          className="rounded-md p-1 text-sidebar-muted hover:bg-accent hover:text-foreground"
          aria-label={collapsed ? t("shell.expandSidebar") : t("shell.collapseSidebar")}
        >
          {collapsed ? <ChevronsRight className="h-4 w-4" /> : <ChevronsLeft className="h-4 w-4" />}
        </button>
      </div>

      <nav className="flex flex-1 flex-col gap-4 px-2 pb-6 pt-2">
        {groups.map((g, gi) => (
          <div key={gi} className="flex flex-col gap-0.5">
            {g.label && !collapsed && (
              <div className="px-3 pb-1 pt-2 text-[11px] font-medium uppercase tracking-wider text-sidebar-muted">{t(g.label)}</div>
            )}
            {g.label && collapsed && <div className="mx-3 my-2 border-t border-sidebar-border" />}
            {g.items.map(({ to, label, icon: Icon, end }) => (
              <NavLink
                key={`${to}-${label}`}
                to={to}
                end={end}
                title={collapsed ? t(label) : undefined}
                className={({ isActive }) =>
                  cn(
                    "group flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
                    isActive
                      ? "bg-sidebar-active text-foreground shadow-sidebar-active [&_svg]:text-primary"
                      : "text-sidebar-muted hover:bg-accent hover:text-foreground",
                  )
                }
              >
                <Icon className="h-4 w-4 shrink-0" />
                {!collapsed && <span className="truncate">{t(label)}</span>}
              </NavLink>
            ))}
          </div>
        ))}
        {switchTo ? (
          <div className="mt-auto border-t border-sidebar-border pt-3">
            <Link
              to={switchTo.to}
              className="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-muted hover:bg-accent hover:text-foreground"
            >
              <ArrowLeftRight className="h-4 w-4 shrink-0" />
              {!collapsed && <span className="truncate">{switchTo.label}</span>}
            </Link>
          </div>
        ) : null}
      </nav>
    </aside>
  );
}
