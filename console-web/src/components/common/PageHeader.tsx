import type { ReactNode } from "react";

/** Title + description on the left, actions on the right. */
export function PageHeader({
  title,
  description,
  actions,
  back,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  /** Optional breadcrumb/back link rendered above the title. */
  back?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-2 border-b border-border pb-5 sm:flex-row sm:items-end sm:justify-between sm:gap-6">
      <div className="min-w-0">
        {back ? <div className="mb-2 text-sm">{back}</div> : null}
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{title}</h1>
        {description ? <p className="mt-1 max-w-3xl text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}
