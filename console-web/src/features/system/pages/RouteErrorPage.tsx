import { isRouteErrorResponse, Link, useRouteError } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Button } from "@/components/ui/button";
import { NotFoundPage } from "./NotFoundPage";

/** Route-level error boundary: a render or loader failure shows this, not a blank page. */
export function RouteErrorPage() {
  const { t } = useTranslation();
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />;
  const message = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : errorMessage(error);
  return (
    <main role="alert" className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-8 text-center">
      <h1 className="text-2xl font-semibold tracking-tight">{t("errors.somethingWentWrong")}</h1>
      <p className="max-w-lg font-mono text-sm text-muted-foreground">{message}</p>
      <div className="flex gap-2">
        <Button variant="outline" onClick={() => window.location.reload()}>
          {t("common.retry")}
        </Button>
        <Button asChild>
          <Link to="/">{t("errors.backHome")}</Link>
        </Button>
      </div>
    </main>
  );
}
