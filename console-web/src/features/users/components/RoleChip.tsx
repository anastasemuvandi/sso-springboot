import { X } from "lucide-react";

/** A removable chip (roles, groups). */
export function RoleChip({ label, onRemove, title, disabled }: { label: string; onRemove?: () => void; title?: string; disabled?: boolean }) {
  if (!onRemove) {
    return <span className="inline-flex items-center rounded-md border bg-secondary px-2 py-0.5 text-xs">{label}</span>;
  }
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onRemove}
      title={title}
      aria-label={title ? `${title}: ${label}` : label}
      className="group inline-flex items-center gap-1 rounded-md border bg-background px-2 py-0.5 text-xs hover:border-destructive hover:bg-destructive/10 disabled:opacity-50"
    >
      <span>{label}</span>
      <X className="h-3 w-3 text-muted-foreground group-hover:text-destructive" />
    </button>
  );
}
