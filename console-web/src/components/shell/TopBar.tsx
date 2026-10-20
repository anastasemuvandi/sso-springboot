import { useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronDown, LogOut, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useSession } from "@/auth/session";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { LanguageSwitcher } from "./LanguageSwitcher";
import { ThemeToggle } from "./ThemeToggle";
import { SignOutForm } from "./SignOutForm";

export function TopBar({ area }: { area: "platform" | "institution" }) {
  const { t } = useTranslation();
  const session = useSession();
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const signOut = useRef<HTMLFormElement>(null);

  function onSearch(e: FormEvent) {
    e.preventDefault();
    const term = q.trim();
    if (!term) return;
    navigate(`${area === "institution" ? "/institution/staff" : "/users"}?q=${encodeURIComponent(term)}`);
  }

  const who = session.name || session.username || "?";

  return (
    <header className="flex h-14 items-center gap-3 border-b border-border bg-background/90 px-4 backdrop-blur">
      <div className="hidden items-center gap-2 lg:flex">
        <span className="inline-flex h-8 items-center gap-2 rounded-md border border-border bg-background px-2.5 text-sm font-medium">
          <span className="grid h-4 w-4 place-items-center rounded-sm bg-primary/15 text-[10px] font-bold text-primary">G</span>
          {t("shell.government")}
        </span>
        <span className="inline-flex h-8 items-center gap-2 rounded-md border border-border bg-background px-2.5 text-sm text-muted-foreground">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
          {area === "institution" && session.institutionSlug
            ? t("shell.institutionBadge", { slug: session.institutionSlug })
            : "government-internal"}
        </span>
      </div>

      <form onSubmit={onSearch} className="ml-auto flex max-w-[420px] flex-1 items-center" role="search">
        <div className="relative w-full">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t("shell.searchPlaceholder")}
            aria-label={t("shell.search")}
            className="h-8 w-full rounded-md border border-border bg-background pl-9 pr-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
          />
        </div>
      </form>

      <LanguageSwitcher />
      <ThemeToggle />

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className="inline-flex h-8 items-center gap-2 rounded-md border border-border bg-background pl-1 pr-2.5 text-sm font-medium hover:bg-accent"
            data-testid="user-menu"
          >
            <span className="grid h-6 w-6 place-items-center rounded-full bg-primary/15 text-[11px] font-semibold text-primary">
              {who.slice(0, 1).toUpperCase()}
            </span>
            <span className="max-w-[140px] truncate" data-testid="current-user">
              {session.username ?? who}
            </span>
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuLabel>
            <div className="truncate text-sm text-foreground">{session.name ?? session.username}</div>
            <div className="truncate font-normal">
              {session.platformAdmin ? t("shell.platformAdmin") : session.institutionAdmin ? t("shell.institutionAdmin") : ""}
            </div>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => signOut.current?.submit()}>
            <LogOut className="h-4 w-4" />
            {t("shell.signOut")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <SignOutForm ref={signOut} />
    </header>
  );
}
