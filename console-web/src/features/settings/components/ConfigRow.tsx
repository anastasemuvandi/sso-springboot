import { isValidElement, type ReactNode } from "react";

import { Badge } from "@/components/ui/badge";

/** label: value, with arrays as chips, booleans as badges and objects as JSON. */
export function ConfigRow({ label, value }: { label: string; value: unknown }) {
  let display: ReactNode;
  if (isValidElement(value)) display = value;
  else if (value === undefined || value === null || value === "") display = <span className="text-muted-foreground">—</span>;
  else if (Array.isArray(value))
    display =
      value.length === 0 ? (
        <span className="text-muted-foreground">[]</span>
      ) : (
        <div className="flex flex-wrap gap-1">
          {value.map((v, i) => (
            <Badge key={i} variant="outline" className="font-mono text-xs">
              {String(v)}
            </Badge>
          ))}
        </div>
      );
  else if (typeof value === "boolean") display = <Badge variant={value ? "secondary" : "outline"}>{value ? "true" : "false"}</Badge>;
  else if (typeof value === "object") display = <pre className="overflow-auto rounded-md bg-muted p-2 font-mono text-xs">{JSON.stringify(value, null, 2)}</pre>;
  else display = <code className="text-xs">{String(value)}</code>;
  return (
    <div className="grid grid-cols-1 gap-1 py-1 sm:grid-cols-[220px_1fr] sm:gap-3">
      <div className="font-mono text-xs text-muted-foreground">{label}</div>
      <div className="min-w-0">{display}</div>
    </div>
  );
}
