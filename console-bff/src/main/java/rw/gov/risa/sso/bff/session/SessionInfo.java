package rw.gov.risa.sso.bff.session;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the SPA may know about its session — COSMETIC ONLY (which console to show, which buttons to
 * hide). It is decoded from the access token server-side without being trusted for anything: the
 * wrapper re-verifies the token and enforces every rule on every call.
 *
 * <p>camelCase on purpose: this is the BFF's own contract with {@code console-web}
 * ({@code src/auth/session.ts}), not a wrapper API.
 */
public record SessionInfo(
        @JsonProperty("authenticated") boolean authenticated,
        @JsonProperty("username") String username,
        @JsonProperty("name") String name,
        @JsonProperty("email") String email,
        @JsonProperty("roles") List<String> realmRoles,
        @JsonProperty("platformRoles") List<String> platformRoles,
        @JsonProperty("platformAdmin") boolean platformAdmin,
        @JsonProperty("institutionAdmin") boolean institutionAdmin,
        @JsonProperty("institutionSlug") String institution,
        @JsonProperty("institutions") List<String> institutions,
        @JsonProperty("landing") String landing,
        @JsonProperty("expiresAt") String expiresAt) {
}
