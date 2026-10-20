package rw.gov.risa.sso.wrapper.api.dashboard;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.service.platformops.dashboard.DashboardService;
import rw.gov.risa.sso.wrapper.service.platformops.dashboard.DashboardService.DashboardMetrics;
import rw.gov.risa.sso.wrapper.service.platformops.dashboard.DashboardService.LoginActivityItem;
import rw.gov.risa.sso.wrapper.service.platformops.dashboard.DashboardService.LoginStats;
import rw.gov.risa.sso.wrapper.service.platformops.dashboard.DashboardService.SessionInfo;
import rw.gov.risa.sso.wrapper.service.platformops.overview.OverviewView;
import rw.gov.risa.sso.wrapper.service.platformops.overview.PlatformOverviewService;

/** {@code /dashboard/*} (admin tier): the platform overview and live Keycloak metrics. */
@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final PlatformOverviewService overview;

    public DashboardController(DashboardService dashboard, PlatformOverviewService overview) {
        this.dashboard = dashboard;
        this.overview = overview;
    }

    @GetMapping("/overview")
    @RateLimit("60/minute")
    @Operation(summary = "What needs a platform admin's attention, realm-wide: leavers, password setup, logins, "
            + "access per application. Four independent sections, each with available/error; cached 60 s")
    public OverviewView overview(
            @Parameter(description = "Bypass the 60-second cache") @RequestParam(defaultValue = "false") boolean refresh) {
        return overview.overview(refresh);
    }

    @GetMapping("/metrics")
    @RateLimit("120/minute")
    @Operation(summary = "Aggregated dashboard metrics")
    public DashboardMetrics metrics() {
        return dashboard.metrics();
    }

    @GetMapping("/users/count")
    @RateLimit("120/minute")
    @Operation(summary = "Total number of users")
    public Map<String, Integer> usersCount() {
        return Map.of("total_users", dashboard.usersCount());
    }

    @GetMapping("/clients/count")
    @RateLimit("120/minute")
    @Operation(summary = "Total number of clients")
    public Map<String, Integer> clientsCount() {
        return Map.of("total_clients", dashboard.clientsCount());
    }

    @GetMapping("/sessions")
    @RateLimit("120/minute")
    @Operation(summary = "Active sessions per client")
    public SessionInfo sessions() {
        return dashboard.sessions();
    }

    @GetMapping("/logins/recent")
    @RateLimit("120/minute")
    @Operation(summary = "Recent login activity (last 24 h)")
    public List<LoginActivityItem> recentLogins(
            @RequestParam(name = "max_items", defaultValue = "20") @Min(1) @Max(100) int maxItems) {
        return dashboard.recentLogins(maxItems);
    }

    @GetMapping("/logins/stats")
    @RateLimit("60/minute")
    @Operation(summary = "Login statistics per hour (24h) or per day (7d, 30d)")
    public LoginStats loginStats(@RequestParam(defaultValue = "7d") String period) {
        return dashboard.loginStats(period);
    }

    @GetMapping("/activity/24h")
    @RateLimit("120/minute")
    @Operation(summary = "24-hour activity: logins, logouts, registrations, failed logins")
    public Map<String, Object> activity24h() {
        return dashboard.activity24h();
    }

    @GetMapping("/events")
    @RateLimit("60/minute")
    @Operation(summary = "Keycloak realm events (not the audit trail); event_types is comma-separated")
    public List<Map<String, Object>> events(
            @RequestParam(name = "event_types", required = false) String eventTypes,
            @RequestParam(name = "max_events", defaultValue = "200") int maxEvents) {
        return dashboard.events(eventTypes, maxEvents);
    }

    @GetMapping("/admin/overview")
    @RateLimit("60/minute")
    @Operation(summary = "Admin dashboard: counts, latest events and system health")
    public Map<String, Object> adminOverview() {
        return dashboard.adminOverview();
    }
}
