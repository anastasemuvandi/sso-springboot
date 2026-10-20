import { useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

const KEY = "gorsso.console.theme";

function initial(): "light" | "dark" {
  try {
    const v = window.localStorage.getItem(KEY);
    if (v === "light" || v === "dark") return v;
  } catch {
    /* ignore */
  }
  return "light";
}

export function ThemeToggle() {
  const { t } = useTranslation();
  const [theme, setTheme] = useState<"light" | "dark">(initial);
  useEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    try {
      window.localStorage.setItem(KEY, theme);
    } catch {
      /* ignore */
    }
  }, [theme]);
  return (
    <Button
      variant="outline"
      size="icon"
      className="h-8 w-8"
      aria-label={t("shell.toggleTheme")}
      onClick={() => setTheme((v) => (v === "dark" ? "light" : "dark"))}
    >
      {theme === "dark" ? <Sun /> : <Moon />}
    </Button>
  );
}
