package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.Collection;

/**
 * Redirect-URI allow-listing (RFC 6749 §10.6). The rule is an EXACT, case-sensitive string match against
 * the registered URIs (spec §5.1). Only when {@code allowWildcards} is on (REDIRECT_URI_ALLOW_WILDCARDS)
 * is a registered value ending in {@code *} honoured as a prefix, as Keycloak does — and even then the
 * prefix must end at a path boundary so {@code https://app/*} never admits {@code https://app.evil}.
 */
public final class RedirectUris {
    private RedirectUris() {
    }

    public static boolean isRegistered(String uri, Collection<String> registered, boolean allowWildcards) {
        if (uri == null || uri.isBlank() || registered == null) {
            return false;
        }
        for (String pattern : registered) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (pattern.equals(uri)) {
                return true;
            }
            if (allowWildcards && pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (!prefix.isEmpty() && (prefix.endsWith("/") || prefix.endsWith("?") || prefix.endsWith("#"))
                        && uri.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
