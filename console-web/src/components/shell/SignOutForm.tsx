import { forwardRef } from "react";

import { readCookie, XSRF_COOKIE } from "@/api/csrf";
import { LOGOUT_URL } from "@/auth/session";

/**
 * Sign out = a top-level POST to the BFF's logout (Spring Security), carrying
 * the CSRF token as `_csrf`. A form post, not fetch, so the BFF's redirect
 * chain (end-session at the wrapper/Keycloak) is followed by the browser.
 */
export const SignOutForm = forwardRef<HTMLFormElement>(function SignOutForm(_props, ref) {
  return (
    <form ref={ref} method="post" action={LOGOUT_URL} className="hidden" aria-hidden>
      <input type="hidden" name="_csrf" value={readCookie(XSRF_COOKIE) ?? ""} />
    </form>
  );
});
