package rw.gov.risa.sso.wrapper.cli;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.AdminRest;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.AdminSession;
import rw.gov.risa.sso.wrapper.service.platformops.verify.KeycloakDirectVerifier;

/**
 * {@code verify --institution MINE --institution THEIRS [--username U] [--password P] [--client-id C]
 * [--client-secret S] [--department D] [--url URL] [--realm REALM] [--admin-user U --admin-password P]}: the
 * Keycloak-direct acceptance test (spec §5.3). Logs in as a REAL institution admin (password grant; default
 * {@code <mine>.admin}, password from {@code --password} or {@code VERIFY_PASSWORD}) and calls the Keycloak
 * Admin API DIRECTLY — no wrapper — for the nine cases of {@link KeycloakDirectVerifier}. Prints a
 * "9/9 passed" report. Exit 0 only when all nine ran and passed; 1 otherwise; 2 when the login fails.
 *
 * <p>The login client defaults to the wrapper's own {@code KEYCLOAK_CLIENT_ID} and secret (it allows
 * direct grants); {@code --client-id}/{@code VERIFY_CLIENT_ID} with {@code --client-secret}/
 * {@code VERIFY_CLIENT_SECRET} pick another one. Ids are looked up with the service account (or
 * {@code --admin-user}), never with the token under test.
 */
@Component
public class VerifyCommand extends PrintingCommand {

    private final SsoProperties sso;
    private final KeycloakAdminFactory factory;

    public VerifyCommand(SsoProperties sso, KeycloakAdminFactory factory) {
        this.sso = sso;
        this.factory = factory;
    }

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "Keycloak-direct acceptance: log in as an institution admin and call the Admin API with no wrapper "
                + "(9 cases) --institution MINE --institution THEIRS [--username U] [--password P] [--client-id C] "
                + "[--client-secret S] [--department D]";
    }

    @Override
    protected Set<String> flags() {
        return Set.of();
    }

    @Override
    protected Set<String> options() {
        Set<String> o = new HashSet<>(AdminIdentityOptions.OPTIONS);
        o.addAll(Set.of("institution", "username", "password", "client-id", "client-secret", "department"));
        return o;
    }

    @Override
    protected int execute(CliArgs args) {
        List<String> slugs = args.all("institution");
        if (slugs.isEmpty()) {
            slugs = List.of("minagri", "minisante");
        }
        if (slugs.size() < 2) {
            out.println("verify needs two institutions to prove isolation: --institution minagri --institution minisante");
            return USAGE;
        }
        String mine = slugs.get(0);
        String theirs = slugs.get(1);
        String username = args.value("username").orElse(mine + ".admin");
        String password = args.value("password").orElseGet(() -> AdminIdentityOptions.env("VERIFY_PASSWORD"));
        if (password == null) {
            out.println("ERROR: give the institution admin's password with --password or VERIFY_PASSWORD.");
            return USAGE;
        }
        String clientId = args.value("client-id").orElseGet(() -> {
            String env = AdminIdentityOptions.env("VERIFY_CLIENT_ID");
            return env != null ? env : sso.keycloak().clientId();
        });
        String clientSecret = args.value("client-secret").orElseGet(() -> {
            String env = AdminIdentityOptions.env("VERIFY_CLIENT_SECRET");
            if (env != null) {
                return env;
            }
            return clientId.equals(sso.keycloak().clientId()) ? sso.keycloak().clientSecret() : null;
        });
        String url = AdminIdentityOptions.url(args, sso);
        String realm = AdminIdentityOptions.realm(args, sso);

        AdminRest.Result login = AdminRest.passwordGrant(url, realm, clientId, clientSecret, username, password);
        String token = login.body().path("access_token").asText(null);
        if (!login.ok() || token == null) {
            out.println("  ! cannot log in as " + username + " via " + clientId + ": HTTP " + login.status() + " "
                    + login.body().path("error").asText("") + " " + login.body().path("error_description").asText(""));
            return 2;
        }
        try (AdminSession lookup = AdminIdentityOptions.open(args, sso, factory)) {
            KeycloakDirectVerifier.Report report = new KeycloakDirectVerifier(lookup.rest()).verify(
                    new KeycloakDirectVerifier.Options(mine, theirs, args.value("department").orElse(null), username,
                            token));
            KeycloakDirectVerifier.render(report).forEach(out::println);
            return report.allPassed() ? 0 : 1;
        }
    }
}
