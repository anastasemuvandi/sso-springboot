/**
 * The provisioning selection, shared by the employee list and the review page:
 * React state over the pure module, mirrored to sessionStorage per institution
 * so it survives paging, navigation to /provision and a reload.
 */
import { useCallback, useEffect, useState } from "react";

import * as sel from "./state/employeeSelection";

function storage(): Storage | null {
  try {
    return typeof window !== "undefined" ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

export function useProvisionSelection(institution: string, max: number = sel.DEFAULT_MAX_BATCH) {
  const [state, setState] = useState<sel.SelectionState>(() => sel.loadSelection(storage(), institution, max));

  useEffect(() => {
    setState(sel.loadSelection(storage(), institution, max));
  }, [institution, max]);

  const commit = useCallback(
    (next: sel.SelectionState) => {
      setState(next);
      sel.saveSelection(storage(), institution, next);
    },
    [institution],
  );

  return { state, commit };
}
