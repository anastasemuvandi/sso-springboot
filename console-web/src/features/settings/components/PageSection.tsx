import type { ReactNode } from "react";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export function PageSection({ id, title, description, actions, children }: { id?: string; title: string; description?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <Card id={id} className="scroll-mt-4">
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3 space-y-0">
        <div className="space-y-1.5">
          <CardTitle>{title}</CardTitle>
          {description ? <CardDescription>{description}</CardDescription> : null}
        </div>
        {actions}
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}
