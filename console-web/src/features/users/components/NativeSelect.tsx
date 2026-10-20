import { forwardRef, type SelectHTMLAttributes } from "react";
import { cn } from "@/lib/utils";

/** A styled native <select> — simple, accessible and testable for long pickers. */
export const NativeSelect = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(({ className, ...props }, ref) => (
  <select
    ref={ref}
    className={cn(
      "flex h-9 w-full rounded-md border border-input bg-background px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40 disabled:opacity-50",
      className,
    )}
    {...props}
  />
));
NativeSelect.displayName = "NativeSelect";
