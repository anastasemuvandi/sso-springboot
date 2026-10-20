/**
 * What happens when an API call answers 401.
 *
 * The BFF refreshes tokens itself; a 401 reaching the SPA means that refresh
 * failed (or the session is gone). Re-check `/bff/session` once and, if the
 * session really is gone, send the browser to log in again, returning here.
 * Concurrent 401s share one re-check, so a page with ten queries redirects once.
 */
import { browser } from "./navigation";
import { fetchSession, loginUrl } from "./session";

let inflight: Promise<void> | null = null;

export function handleUnauthorized(): Promise<void> {
  if (inflight) return inflight;
  inflight = (async () => {
    let authenticated = false;
    try {
      authenticated = (await fetchSession()).authenticated;
    } catch {
      authenticated = false;
    }
    if (!authenticated) browser.assign(loginUrl(browser.currentPath()));
  })().finally(() => {
    // Allow a later, independent 401 to re-check.
    setTimeout(() => {
      inflight = null;
    }, 0);
  });
  return inflight;
}
