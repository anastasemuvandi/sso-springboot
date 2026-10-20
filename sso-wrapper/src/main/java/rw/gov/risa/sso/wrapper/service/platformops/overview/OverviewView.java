package rw.gov.risa.sso.wrapper.service.platformops.overview;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

import rw.gov.risa.sso.wrapper.policy.overview.AccessSection;
import rw.gov.risa.sso.wrapper.policy.overview.AppAccessRow;
import rw.gov.risa.sso.wrapper.policy.overview.LeaverReason;
import rw.gov.risa.sso.wrapper.policy.overview.LeaverRow;
import rw.gov.risa.sso.wrapper.policy.overview.LeaversSection;
import rw.gov.risa.sso.wrapper.policy.overview.LoginSection;
import rw.gov.risa.sso.wrapper.policy.overview.OverviewReport;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupCounts;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupRow;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupSection;
import rw.gov.risa.sso.wrapper.policy.overview.UserCount;

/**
 * The wire form of {@link OverviewReport} (reference {@code app/schema/overview.py}, snake_case), shared
 * by {@code GET /dashboard/overview} and — for the institution-admin team — {@code GET
 * /institution-admin/overview}: build an {@link OverviewReport} with the {@code policy.overview} rules and
 * return {@link #of(OverviewReport)}. Every section carries {@code available} and {@code error}.
 */
@Schema(name = "PlatformOverview", description = "The overview response (reference schema `Overview`); "
        + "shared by GET /dashboard/overview and, for reuse, GET /institution-admin/overview")
public record OverviewView(
        @Schema(description = "Null for the platform (realm-wide) overview") String institution,
        @JsonProperty("generated_at") String generatedAt,
        @Schema(description = "Accounts in scope") Integer members,
        Leavers leavers,
        @JsonProperty("password_setup") PasswordSetup passwordSetup,
        Logins logins,
        Access access) {

    public static OverviewView of(OverviewReport r) {
        return new OverviewView(r.institution(), r.generatedAt(), r.members(), Leavers.of(r.leavers()),
                PasswordSetup.of(r.passwordSetup()), Logins.of(r.logins()), Access.of(r.access()));
    }

    @Schema(name = "LeaverRow")
    public record Leaver(
            @JsonProperty("employee_number") String employeeNumber, String name, String institution, String sub,
            String username, boolean enabled, List<String> applications, List<String> reasons) {
        static Leaver of(LeaverRow r) {
            return new Leaver(r.employeeNumber(), r.name(), r.institution(), r.sub(), r.username(), r.enabled(),
                    r.applications(), r.reasons().stream().map(LeaverReason::code).toList());
        }
    }

    @Schema(name = "LeaversSection")
    public record Leavers(
            boolean available, String error,
            @JsonProperty("applications_checked") boolean applicationsChecked, int total, boolean truncated,
            List<Leaver> items) {
        static Leavers of(LeaversSection s) {
            return new Leavers(s.available(), s.error(), s.applicationsChecked(), s.total(), s.truncated(),
                    s.items().stream().map(Leaver::of).toList());
        }
    }

    @Schema(name = "PasswordSetupCounts")
    public record Counts(int total, int sent, int failed, @JsonProperty("not_configured") int notConfigured,
                         int unknown) {
        static Counts of(PasswordSetupCounts c) {
            return c == null ? null : new Counts(c.total(), c.sent(), c.failed(), c.notConfigured(), c.unknown());
        }
    }

    @Schema(name = "PasswordSetupRow")
    public record PasswordRow(
            String sub, String username, String email, boolean enabled,
            @JsonProperty("employee_number") String employeeNumber,
            @JsonProperty("setup_email") String setupEmail, String at, boolean resendable) {
        static PasswordRow of(PasswordSetupRow r) {
            return new PasswordRow(r.sub(), r.username(), r.email(), r.enabled(), r.employeeNumber(), r.setupEmail(),
                    r.at(), r.resendable());
        }
    }

    @Schema(name = "PasswordSetupSection")
    public record PasswordSetup(boolean available, String error, Counts counts, List<PasswordRow> items) {
        static PasswordSetup of(PasswordSetupSection s) {
            return new PasswordSetup(s.available(), s.error(), Counts.of(s.counts()),
                    s.items().stream().map(PasswordRow::of).toList());
        }
    }

    @Schema(name = "UserCount")
    public record Count(String sub, String username, Integer failures) {
        static Count of(UserCount u) {
            return new Count(u.sub(), u.username(), u.failures());
        }
    }

    @Schema(name = "LoginSection")
    public record Logins(
            boolean available, String error,
            @JsonProperty("logins_24h") int logins24h, @JsonProperty("logins_7d") int logins7d,
            @JsonProperty("failed_24h") int failed24h, @JsonProperty("failed_7d") int failed7d,
            @JsonProperty("people_24h") int people24h, @JsonProperty("people_7d") int people7d,
            @JsonProperty("most_failures_7d") List<Count> mostFailures7d,
            @JsonProperty("locked_now") List<Count> lockedNow,
            boolean truncated,
            @JsonProperty("active_sessions") Integer activeSessions) {
        static Logins of(LoginSection s) {
            return new Logins(s.available(), s.error(), s.logins24h(), s.logins7d(), s.failed24h(), s.failed7d(),
                    s.people24h(), s.people7d(), s.mostFailures7d().stream().map(Count::of).toList(),
                    s.lockedNow().stream().map(Count::of).toList(), s.truncated(), s.activeSessions());
        }
    }

    @Schema(name = "AppAccessRow")
    public record App(
            @JsonProperty("client_id") String clientId, String name,
            @JsonProperty("cross_cutting") boolean crossCutting, String kind, Integer people, int named,
            @JsonProperty("via_group") Integer viaGroup, List<String> groups, int revoked,
            @JsonProperty("active_sessions") Integer activeSessions) {
        static App of(AppAccessRow r) {
            return new App(r.clientId(), r.name(), r.crossCutting(), r.kind(), r.people(), r.named(), r.viaGroup(),
                    r.groups(), r.revoked(), r.activeSessions());
        }
    }

    @Schema(name = "AccessSection")
    public record Access(boolean available, String error, List<App> items) {
        static Access of(AccessSection s) {
            return new Access(s.available(), s.error(), s.items().stream().map(App::of).toList());
        }
    }
}
