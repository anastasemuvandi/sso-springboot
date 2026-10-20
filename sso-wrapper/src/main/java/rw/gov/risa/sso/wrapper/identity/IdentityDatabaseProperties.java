package rw.gov.risa.sso.wrapper.identity;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The optional {@code sso_identity} database (the IPPIS mirror). NEVER Keycloak's database: there
 * is deliberately no default URL, so "unset" means "no mirror", never "the database next door".
 *
 * @param databaseUrl IDENTITY_DATABASE_URL — {@code jdbc:postgresql://host:5432/sso_identity}, or the
 *                    reference form {@code postgresql[+driver]://user:pw@host:port/sso_identity}
 * @param username    IDENTITY_DATABASE_USER (overrides a user in the URL)
 * @param password    IDENTITY_DATABASE_PASSWORD (or _FILE; overrides a password in the URL)
 * @param migrate     IDENTITY_DB_MIGRATE — run the Flyway migrations at startup (advisory-locked, safe
 *                    across replicas)
 * @param poolSize    IDENTITY_DB_POOL_SIZE
 */
@ConfigurationProperties(prefix = "sso.identity")
public record IdentityDatabaseProperties(
        @DefaultValue("") String databaseUrl,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("true") boolean migrate,
        @DefaultValue("10") int poolSize) {

    public boolean configured() {
        return databaseUrl != null && !databaseUrl.isBlank();
    }

    /** JDBC URL + credentials resolved from either URL form. */
    public record Connection(String jdbcUrl, String username, String password) {
        @Override
        public String toString() {
            return "Connection[" + jdbcUrl + ", user=" + username + "]";
        }
    }

    public Connection connection() {
        String raw = databaseUrl.strip();
        if (raw.startsWith("jdbc:")) {
            return new Connection(raw, blankToNull(username), blankToNull(password));
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("IDENTITY_DATABASE_URL is not a valid URL (value not logged)");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (!scheme.startsWith("postgres")) {
            throw new IllegalStateException("IDENTITY_DATABASE_URL must be a PostgreSQL URL (jdbc:postgresql://… or "
                    + "postgresql://…), got scheme '" + scheme + "'");
        }
        String user = null;
        String pw = null;
        String info = uri.getRawUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            user = decode(colon >= 0 ? info.substring(0, colon) : info);
            pw = colon >= 0 ? decode(info.substring(colon + 1)) : null;
            if (colon >= 0 && pw.isEmpty() && (password == null || password.isBlank())) {
                // The reference's ':@' trap: an interpolated empty password looks configured and fails
                // authentication on every write.
                throw new IllegalStateException("IDENTITY_DATABASE_URL has an empty password (:@). Set "
                        + "IDENTITY_DATABASE_PASSWORD, or unset the URL (docs/runbooks/create-sso-identity-db.md §4).");
            }
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return new Connection(jdbc,
                username != null && !username.isBlank() ? username : user,
                password != null && !password.isBlank() ? password : pw);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Override
    public String toString() {
        return "IdentityDatabaseProperties[configured=" + configured() + "]";
    }
}
