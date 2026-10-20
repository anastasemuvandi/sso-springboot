import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** "2026-10-09 14:03 UTC" — never the browser locale, so screenshots and audit exports agree. */
export function formatWhen(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return `${d.toISOString().slice(0, 16).replace("T", " ")} UTC`;
}

/** A count that may be unknown: `null`/`undefined` renders as "—", never as 0. */
export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : value.toLocaleString();
}
