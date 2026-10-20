import type { ReactNode } from "react";
import { Label } from "@/components/ui/label";

/** Label + control + help/error text, for react-hook-form forms. */
export function Field({
  label,
  htmlFor,
  help,
  error,
  children,
}: {
  label: ReactNode;
  htmlFor?: string;
  help?: ReactNode;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : help ? (
        <p className="text-xs text-muted-foreground">{help}</p>
      ) : null}
    </div>
  );
}
