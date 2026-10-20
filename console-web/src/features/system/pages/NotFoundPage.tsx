import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { BrandMark } from "@/components/shell/BrandMark";

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-page p-8 text-center">
      <BrandMark className="h-14 w-14" />
      <h1 className="text-3xl font-semibold tracking-tight">404</h1>
      <p className="text-sm text-muted-foreground">{t("errors.notFound")}</p>
      <Button asChild>
        <Link to="/">{t("errors.backHome")}</Link>
      </Button>
    </main>
  );
}

export const Component = NotFoundPage;
