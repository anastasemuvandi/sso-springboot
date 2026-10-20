import { Outlet } from "react-router-dom";

import { ConsoleLayout } from "@/components/shell/ConsoleLayout";
import { InstitutionProvider } from "../context";
import { InstitutionGate } from "./InstitutionGate";

/** /institution/** frame: resolves /me + /permissions once, gates every page on it. */
export function InstitutionLayout() {
  return (
    <InstitutionProvider>
      <ConsoleLayout
        area="institution"
        content={
          <InstitutionGate>
            <Outlet />
          </InstitutionGate>
        }
      />
    </InstitutionProvider>
  );
}
