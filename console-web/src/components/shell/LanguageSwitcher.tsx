import { Languages } from "lucide-react";
import { useTranslation } from "react-i18next";

import { LANGUAGES, setLanguage, type Language } from "@/i18n";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";

export function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const current = (i18n.resolvedLanguage ?? i18n.language) as Language;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" aria-label={t("shell.language")}>
          <Languages />
          <span className="uppercase">{current}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {LANGUAGES.map((lang) => (
          <DropdownMenuItem key={lang} onSelect={() => setLanguage(lang)} aria-current={lang === current}>
            {t(`shell.languages.${lang}`)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
