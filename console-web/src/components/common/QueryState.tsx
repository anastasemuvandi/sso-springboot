import type { ReactNode } from "react";
import { AlertTriangle, Loader2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/** Spinner + label, for a query that is loading. */
export function Loading({ label, className }: { label?: string; className?: string }) {
  const { t } = useTranslation();
  return (
    <div role="status" className={cn("flex items-center gap-2 py-6 text-sm text-muted-foreground", className)}>
      <Loader2 className="h-4 w-4 animate-spin" />
      {label ?? t("common.loading")}
    </div>
  );
}

/** A failed read: says what failed and why, with a retry. Never renders zeros. */
export function ErrorState({
  error,
  title,
  onRetry,
  className,
}: {
  error: unknown;
  title?: string;
  onRetry?: () => void;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <div role="alert" className={cn("rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm", className)}>
      <div className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
        <div className="min-w-0 flex-1">
          <div className="font-medium text-foreground">{title ?? t("common.loadFailed")}</div>
          <div className="mt-1 break-words text-muted-foreground">{errorMessage(error)}</div>
        </div>
        {onRetry ? (
          <Button size="sm" variant="outline" onClick={onRetry}>
            {t("common.retry")}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

/** "Not available — <reason>." for a section that could not be read. */
export function Unavailable({ error }: { error?: string | null }) {
  const { t } = useTranslation();
  return (
    <p className="text-sm text-muted-foreground" data-testid="section-unavailable">
      {error ? t("common.notAvailableBecause", { reason: error }) : t("common.notAvailable")}
    </p>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-muted-foreground">{children}</p>;
}
