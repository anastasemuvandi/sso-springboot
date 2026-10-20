import { useEffect, useMemo, useRef, type ReactNode } from "react";
import { BffAuthClient } from "./bffClient";
import type { AuthClient } from "./client";
import { GorssoContext } from "./context";
import { PkceAuthClient } from "./pkceClient";
import type { GorssoConfig } from "./types";

export function createAuthClient(config: GorssoConfig): AuthClient {
  return config.mode === "bff" ? new BffAuthClient(config) : new PkceAuthClient(config);
}

export interface GorssoProviderProps {
  config: GorssoConfig;
  /** Inject a ready client instead (tests, or sharing one client outside React). */
  client?: AuthClient;
  children?: ReactNode;
}

/**
 * Put this ABOVE your router's routes (inside `<BrowserRouter>` if you want to pass a
 * router-aware `onRedirectCallback`). It finishes a login callback (pkce) or loads the BFF
 * session (bff) once on mount.
 */
export function GorssoProvider({ config, client: injected, children }: GorssoProviderProps) {
  // The client is created once; changing `config` after mount is not supported.
  const configRef = useRef(config);
  const client = useMemo(() => injected ?? createAuthClient(configRef.current), [injected]);

  useEffect(() => {
    void client.init();
    return () => client.dispose();
  }, [client]);

  return <GorssoContext.Provider value={client}>{children}</GorssoContext.Provider>;
}
