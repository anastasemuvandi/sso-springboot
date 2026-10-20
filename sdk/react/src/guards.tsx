import { useEffect, type ReactNode } from "react";
import { currentLocation } from "./client";
import { useAuth } from "./hooks";

export interface RequireAuthProps {
  children?: ReactNode;
  /** Shown while the session loads or the login redirect is starting. */
  fallback?: ReactNode;
  /** Shown when login failed (status `error`). Default: a short message. */
  errorFallback?: ReactNode;
  /** Start login automatically when anonymous (default true). */
  redirect?: boolean;
  /** Where to come back to. Default: the current location. */
  returnTo?: string;
}

/**
 * Renders `children` only for an authenticated caller; otherwise starts login. Works with React
 * Router v6 as `element={<RequireAuth><Outlet /></RequireAuth>}`.
 */
export function RequireAuth({ children, fallback = null, errorFallback, redirect = true, returnTo }: RequireAuthProps) {
  const { status, isAuthenticated, login, error } = useAuth();
  useEffect(() => {
    if (status === "unauthenticated" && redirect) void login(returnTo ?? currentLocation());
  }, [status, redirect, login, returnTo]);
  if (isAuthenticated) return <>{children}</>;
  if (status === "error") {
    return <>{errorFallback ?? <p role="alert">Sign-in failed: {error?.code ?? "error"}</p>}</>;
  }
  return <>{fallback}</>;
}

export interface RequireRoleProps {
  /** One role (`inst:minagri:viewer`, `inst:minagri:*`, `client:editor`). */
  role?: string;
  /** Or any of several. */
  anyOf?: string[];
  children?: ReactNode;
  /** Shown to an authenticated caller without the role. Default: a 403 message. */
  forbidden?: ReactNode;
  /** Passed to the inner `<RequireAuth>`. */
  fallback?: ReactNode;
}

/**
 * `<RequireAuth>` + a role check. COSMETIC: the API must enforce the same rule.
 */
export function RequireRole({ role, anyOf, children, forbidden, fallback }: RequireRoleProps) {
  return (
    <RequireAuth fallback={fallback}>
      <RoleGate role={role} anyOf={anyOf} forbidden={forbidden}>
        {children}
      </RoleGate>
    </RequireAuth>
  );
}

function RoleGate({ role, anyOf, children, forbidden }: Omit<RequireRoleProps, "fallback">) {
  const auth = useAuth();
  const required = [...(role ? [role] : []), ...(anyOf ?? [])];
  const ok = required.length === 0 || auth.hasAnyRole(required);
  if (ok) return <>{children}</>;
  return <>{forbidden ?? <p role="alert">You do not have access to this page (403).</p>}</>;
}
