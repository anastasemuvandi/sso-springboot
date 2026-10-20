package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.keycloak.representations.idm.ClientRepresentation;

/**
 * What the gateway needs to know about a realm client. Never carries the secret.
 *
 * @param postLogoutRedirectUris the {@code post.logout.redirect.uris} attribute ({@code ##}-separated;
 *                               absent or {@code +} = the redirect URIs, Keycloak's convention)
 */
public record RegisteredClient(String uuid, String clientId, String name, boolean enabled, boolean publicClient,
                               boolean bearerOnly, List<String> redirectUris, List<String> postLogoutRedirectUris,
                               String rootUrl, String baseUrl) {

    public static final String POST_LOGOUT_ATTR = "post.logout.redirect.uris";

    public RegisteredClient {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        postLogoutRedirectUris = postLogoutRedirectUris == null ? List.of() : List.copyOf(postLogoutRedirectUris);
    }

    /** A confidential client must authenticate (spec §7.4). */
    public boolean confidential() {
        return !publicClient;
    }

    /** First registered redirect URI (the reference's {@code redirect_url} on login), or null. */
    public String mainRedirectUrl() {
        return redirectUris.isEmpty() ? null : redirectUris.get(0);
    }

    public static RegisteredClient of(ClientRepresentation c) {
        List<String> redirects = c.getRedirectUris() == null ? List.of() : c.getRedirectUris();
        Map<String, String> attrs = c.getAttributes() == null ? Map.of() : c.getAttributes();
        String raw = attrs.get(POST_LOGOUT_ATTR);
        List<String> postLogout;
        if (raw == null || raw.isBlank() || "+".equals(raw.trim())) {
            postLogout = redirects;
        } else {
            postLogout = new ArrayList<>();
            for (String p : raw.split("##")) {
                if (!p.isBlank()) {
                    postLogout.add(p.trim());
                }
            }
        }
        return new RegisteredClient(c.getId(), c.getClientId(), c.getName(), !Boolean.FALSE.equals(c.isEnabled()),
                Boolean.TRUE.equals(c.isPublicClient()), Boolean.TRUE.equals(c.isBearerOnly()), redirects, postLogout,
                c.getRootUrl(), c.getBaseUrl());
    }
}
