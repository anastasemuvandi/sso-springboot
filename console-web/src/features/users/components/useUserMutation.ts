import { useMutation, useQueryClient } from "@tanstack/react-query";

import { notify } from "@/components/common/toast";
import { userKeys } from "../api";

/**
 * A mutation on one user: toast on success/failure, then re-read everything
 * on that user's page (`userKeys.detail(id)`), plus lists.
 */
export function useUserMutation<V, R = unknown>(userId: string, fn: (v: V) => Promise<R>, success: string | ((v: V) => string)) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (_r, v) => {
      notify.success(typeof success === "function" ? success(v) : success);
      void qc.invalidateQueries({ queryKey: userKeys.detail(userId) });
      void qc.invalidateQueries({ queryKey: [...userKeys.all, "list"] });
    },
    onError: (e) => notify.error(e),
  });
}
