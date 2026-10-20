package rw.gov.risa.sso.wrapper.service.platformops.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.media.Schema;

import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * The dashboard's live numbers (port of {@code api/dashboard.py}), from Keycloak, as the service
 * account. Unlike the reference, nothing here is mocked and a failed read is an error (502/503 via the
 * global handler) — never a silent zero: {@code total_agencies} counts the realm's institution groups and
 * {@code new_users_this_week} counts accounts created in the last 7 days.
 */
@Service
public class DashboardService {

    /** Login times are bucketed in Rwanda time (UTC+2), where the people logging in are. */
    public static final ZoneId ZONE = ZoneId.of("Africa/Kigali");
    static final long HOUR_MS = 3600_000L;
    static final int MAX_EVENTS = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final InstitutionRegistryHolder registry;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public DashboardService(KeycloakExecutors executors, KeycloakDirectory directory, InstitutionRegistryHolder registry,
                            StringRedisTemplate redis) {
        this(executors, directory, registry, redis, Clock.systemUTC());
    }

    DashboardService(KeycloakExecutors executors, KeycloakDirectory directory, InstitutionRegistryHolder registry,
                     StringRedisTemplate redis, Clock clock) {
        this.executors = executors;
        this.directory = directory;
        this.registry = registry;
        this.redis = redis;
        this.clock = clock;
    }

    // ---- DTOs (reference names) --------------------------------------------------------------

    @Schema(name = "DashboardMetrics")
    public record DashboardMetrics(
            @JsonProperty("total_users") int totalUsers,
            @JsonProperty("total_clients") int totalClients,
            @JsonProperty("total_agencies") int totalAgencies,
            @JsonProperty("active_sessions") int activeSessions,
            @JsonProperty("logins_today") int loginsToday,
            @JsonProperty("new_users_this_week") int newUsersThisWeek) {
    }

    @Schema(name = "LoginActivity")
    public record LoginActivityItem(
            String timestamp, String user, @JsonProperty("client_id") String clientId,
            @JsonProperty("ip_address") String ipAddress, String status) {
    }

    @Schema(name = "LoginStats")
    public record LoginStats(String period, List<Map<String, Object>> data, @JsonProperty("total_logins") int totalLogins,
                             @JsonProperty("unique_users") int uniqueUsers) {
    }

    @Schema(name = "SessionInfo")
    public record SessionInfo(@JsonProperty("total_sessions") int totalSessions,
                              @JsonProperty("client_sessions") List<Map<String, Object>> clientSessions) {
    }

    // ---- counts ------------------------------------------------------------------------------

    public int usersCount() {
        return executors.serviceAccount().call(realm -> realm.users().count());
    }

    /** Clients, without Keycloak's {@code security-admin-*} console client (as the reference). */
    public int clientsCount() {
        return executors.serviceAccount().call(realm -> (int) realm.clients().findAll().stream()
                .filter(c -> c.getClientId() == null || !c.getClientId().startsWith("security-admin")).count());
    }

    public SessionInfo sessions() {
        return executors.serviceAccount().call(realm -> {
            List<Map<String, Object>> stats = new ArrayList<>();
            int total = 0;
            for (Map<String, String> s : realm.getClientSessionStats()) {
                Map<String, Object> m = new LinkedHashMap<>(s);
                int active = parseInt(s.get("active"));
                m.put("active", active);
                m.put("offline", parseInt(s.get("offline")));
                total += active;
                stats.add(m);
            }
            return new SessionInfo(total, stats);
        });
    }

    /** {@code GET /dashboard/metrics}. */
    public DashboardMetrics metrics() {
        long now = clock.millis();
        return executors.serviceAccount().call(realm -> {
            int users = realm.users().count();
            int clients = (int) realm.clients().findAll().stream()
                    .filter(c -> c.getClientId() == null || !c.getClientId().startsWith("security-admin")).count();
            int sessions = 0;
            for (Map<String, String> s : realm.getClientSessionStats()) {
                sessions += parseInt(s.get("active"));
            }
            int loginsToday = events(realm, List.of("LOGIN"), now - 24 * HOUR_MS, now, MAX_EVENTS).size();
            int[] newUsers = {0};
            long weekAgo = now - 7 * 24 * HOUR_MS;
            directory.forEachUserPage(realm, 200, false, page -> {
                for (UserRepresentation u : page) {
                    if (u.getCreatedTimestamp() != null && u.getCreatedTimestamp() >= weekAgo) {
                        newUsers[0]++;
                    }
                }
                return true;
            });
            return new DashboardMetrics(users, clients, agencies(realm), sessions, loginsToday, newUsers[0]);
        });
    }

    /** Institutions provisioned in the realm: top-level groups whose name is a registry slug (any casing). */
    int agencies(RealmResource realm) {
        Set<String> known = registry.knownSlugs();
        List<GroupRepresentation> top = realm.groups().groups(0, 10_000);
        if (known.isEmpty()) {
            return top.size();
        }
        Set<String> seen = new HashSet<>();
        for (GroupRepresentation g : top) {
            String name = g.getName() == null ? "" : g.getName().strip().toLowerCase(Locale.ROOT);
            if (known.contains(name)) {
                seen.add(name);
            }
        }
        return seen.size();
    }

    // ---- logins ------------------------------------------------------------------------------

    /** {@code GET /dashboard/logins/recent}: newest LOGIN / LOGIN_ERROR events in the last 24 h. */
    public List<LoginActivityItem> recentLogins(int maxItems) {
        long now = clock.millis();
        return executors.serviceAccount().call(realm -> {
            List<LoginActivityItem> out = new ArrayList<>();
            for (EventRepresentation e : events(realm, List.of("LOGIN", "LOGIN_ERROR"), now - 24 * HOUR_MS, now,
                    maxItems)) {
                String username = e.getDetails() == null ? null : e.getDetails().get("username");
                out.add(new LoginActivityItem(String.valueOf(e.getTime()),
                        username != null ? username : e.getUserId() != null ? e.getUserId() : "Unknown",
                        e.getClientId(), e.getIpAddress(),
                        e.getError() == null && "LOGIN".equals(e.getType()) ? "SUCCESS" : "FAILED"));
            }
            return out;
        });
    }

    /** {@code GET /dashboard/logins/stats}: successful logins per hour (24h) or per day (7d / 30d). */
    public LoginStats loginStats(String period) {
        int hours = switch (period == null ? "" : period) {
            case "24h" -> 24;
            case "30d" -> 720;
            default -> 168;
        };
        String p = period == null ? "7d" : period;
        long now = clock.millis();
        List<EventRepresentation> events = executors.serviceAccount()
                .call(realm -> events(realm, List.of("LOGIN"), now - hours * HOUR_MS, now, MAX_EVENTS));
        Map<String, Integer> buckets = new HashMap<>();
        Set<String> people = new HashSet<>();
        DateTimeFormatter hourFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00").withZone(ZONE);
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);
        for (EventRepresentation e : events) {
            Instant t = Instant.ofEpochMilli(e.getTime());
            buckets.merge(hours == 24 ? hourFmt.format(t) : dayFmt.format(t), 1, Integer::sum);
            people.add(e.getUserId() == null ? "" : e.getUserId());
        }
        List<Map<String, Object>> data = new ArrayList<>();
        Instant nowI = Instant.ofEpochMilli(now);
        if (hours == 24) {
            for (int i = 0; i < 24; i++) {
                String key = hourFmt.format(nowI.minusSeconds(3600L * i));
                data.add(Map.of("time", key, "logins", buckets.getOrDefault(key, 0)));
            }
        } else {
            int days = hours / 24;
            for (int i = 0; i < days; i++) {
                String key = dayFmt.format(nowI.minusSeconds(86_400L * i));
                data.add(Map.of("date", key, "logins", buckets.getOrDefault(key, 0)));
            }
        }
        return new LoginStats(p, data, events.size(), people.size());
    }

    /** {@code GET /dashboard/activity/24h}: logins, logouts, registrations and failures in the last 24 h. */
    public Map<String, Object> activity24h() {
        long now = clock.millis();
        List<EventRepresentation> events = executors.serviceAccount().call(realm -> events(realm,
                List.of("LOGIN", "LOGIN_ERROR", "LOGOUT", "REGISTER"), now - 24 * HOUR_MS, now, MAX_EVENTS));
        int logins = 0;
        int logouts = 0;
        int registrations = 0;
        int failed = 0;
        for (EventRepresentation e : events) {
            switch (e.getType() == null ? "" : e.getType()) {
                case "LOGIN" -> {
                    if (e.getError() != null) {
                        failed++;
                    } else {
                        logins++;
                    }
                }
                case "LOGIN_ERROR" -> failed++;
                case "LOGOUT" -> logouts++;
                case "REGISTER" -> registrations++;
                default -> { }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logins", logins);
        out.put("logouts", logouts);
        out.put("registrations", registrations);
        out.put("failed_logins", failed);
        out.put("total_events", events.size());
        out.put("truncated", events.size() >= MAX_EVENTS);
        return out;
    }

    /** {@code GET /dashboard/events}: Keycloak's raw realm events, newest first ({@code max_events} 1–1000). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events(String eventTypes, int maxEvents) {
        List<String> types = null;
        if (eventTypes != null && !eventTypes.isBlank()) {
            types = new ArrayList<>();
            for (String t : eventTypes.split(",")) {
                if (!t.isBlank()) {
                    types.add(t.strip().toUpperCase(Locale.ROOT));
                }
            }
        }
        List<String> filter = types == null || types.isEmpty() ? null : types;
        int max = Math.min(Math.max(maxEvents, 1), 1000);
        return executors.serviceAccount().call(realm -> realm.getEvents(filter, null, null, null, null, null, 0, max)
                .stream().map(e -> (Map<String, Object>) JSON.convertValue(e, Map.class)).toList());
    }

    /** {@code GET /dashboard/admin/overview}: counts, the latest events and a real health probe. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> adminOverview() {
        Map<String, Object> out = new LinkedHashMap<>();
        executors.serviceAccount().run(realm -> {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("users", realm.users().count());
            List<ClientRepresentation> clients = realm.clients().findAll();
            metrics.put("clients", (int) clients.stream()
                    .filter(c -> c.getClientId() == null || !c.getClientId().startsWith("security-admin")).count());
            int sessions = 0;
            for (Map<String, String> s : realm.getClientSessionStats()) {
                sessions += parseInt(s.get("active"));
            }
            metrics.put("sessions", sessions);
            metrics.put("roles", realm.roles().list().size());
            metrics.put("groups", realm.groups().count().getOrDefault("count", 0L));
            List<EventRepresentation> recent = realm.getEvents(null, null, null, null, null, null, 0, 50);
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("events_count", recent.size());
            activity.put("latest_events", recent.stream().limit(10)
                    .map(e -> (Map<String, Object>) JSON.convertValue(e, Map.class)).toList());
            out.put("metrics", metrics);
            out.put("recent_activity", activity);
        });
        Map<String, Object> health = new LinkedHashMap<>();
        String redisState;
        try {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            redisState = "PONG".equalsIgnoreCase(pong) ? "connected" : "unavailable";
        } catch (RuntimeException e) {
            redisState = "unavailable";
        }
        health.put("status", "connected".equals(redisState) ? "healthy" : "degraded");
        health.put("keycloak", "connected");
        health.put("redis", redisState);
        out.put("system_health", health);
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------

    static List<EventRepresentation> events(RealmResource realm, List<String> types, long fromMs, long toMs, int max) {
        return realm.getEvents(types, null, null, fromMs, toMs, null, 0, max, "desc");
    }

    static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
