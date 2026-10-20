/** Toasts (sonner). Use `notify.success(t("..."))` / `notify.error(e)` from mutations. */
import { toast } from "sonner";
import { errorMessage } from "@/api/errors";

export const notify = {
  success: (msg: string) => toast.success(msg),
  info: (msg: string) => toast(msg),
  error: (e: unknown, prefix?: string) => toast.error(prefix ? `${prefix}: ${errorMessage(e)}` : errorMessage(e)),
};
