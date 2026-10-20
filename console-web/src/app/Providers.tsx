import type { ReactNode } from "react";
import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { Toaster } from "sonner";

import { ConfirmProvider } from "@/components/common/ConfirmDialog";
import { TooltipProvider } from "@/components/ui/tooltip";
import "@/i18n";

/** Everything a page needs around it — used by main.tsx and by component tests. */
export function Providers({ client, children }: { client: QueryClient; children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <TooltipProvider delayDuration={200}>
        <ConfirmProvider>
          {children}
          <Toaster richColors closeButton position="top-right" />
        </ConfirmProvider>
      </TooltipProvider>
    </QueryClientProvider>
  );
}
