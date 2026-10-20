/**
 * Confirm dialogs, imperatively: `const confirm = useConfirm();
 * if (await confirm({ title, description, destructive: true })) { ... }`.
 *
 * Confirming is a courtesy to the admin, not a control: the wrapper decides.
 * Flows the wrapper itself gates (409 + `confirm: true`) still show a dialog
 * with the wrapper's own numbers — see the clients scope editor.
 */
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

export interface ConfirmOptions {
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<ConfirmFn | null>(null);

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const [opts, setOpts] = useState<ConfirmOptions | null>(null);
  const resolver = useRef<((v: boolean) => void) | null>(null);

  const confirm = useCallback<ConfirmFn>((o) => {
    setOpts(o);
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const settle = (v: boolean) => {
    resolver.current?.(v);
    resolver.current = null;
    setOpts(null);
  };

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <AlertDialog open={opts !== null} onOpenChange={(open) => !open && settle(false)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{opts?.title}</AlertDialogTitle>
            {opts?.description ? (
              <AlertDialogDescription asChild>
                <div>{opts.description}</div>
              </AlertDialogDescription>
            ) : null}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => settle(false)}>{opts?.cancelLabel ?? t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction destructive={opts?.destructive} onClick={() => settle(true)}>
              {opts?.confirmLabel ?? t("common.confirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error("useConfirm must be used inside <ConfirmProvider>");
  return ctx;
}
