package rw.gov.risa.sso.wrapper.policy.setuplink;

/** A freshly issued link: the raw token (goes in the email only, never stored) and its record. */
public record IssuedLink(String token, LinkRecord record) {

    /** {@code <apiBaseUrl>/account/setup?token=<token>}. */
    public String url(String apiBaseUrl) {
        String base = apiBaseUrl == null ? "" : apiBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/account/setup?token=" + token;
    }
}
