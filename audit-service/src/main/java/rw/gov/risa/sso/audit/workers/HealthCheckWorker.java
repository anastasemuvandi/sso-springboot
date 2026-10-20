package rw.gov.risa.sso.audit.workers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import rw.gov.risa.sso.audit.config.AuditProperties;
import rw.gov.risa.sso.audit.repository.ServiceHealthRepository;

/**
 * Every 60 s: probe each monitored service and store a {@code service_health} snapshot
 * (reference workers/health_check.py). HTTP status {@code < 400} is UP, otherwise DEGRADED; a
 * connection failure or timeout is DOWN; a service without a URL is UNKNOWN. postgresql is this
 * service's own database ({@code SELECT 1}); redis is a raw RESP {@code PING} (no client library).
 */
@Component
@ConditionalOnProperty(name = "audit.workers.enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckWorker {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckWorker.class);

    public record Probe(String status, Integer responseTimeMs, Map<String, Object> details) {
    }

    private final AuditProperties props;
    private final ServiceHealthRepository repo;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HttpClient http;

    public HealthCheckWorker(AuditProperties props, ServiceHealthRepository repo, JdbcTemplate jdbc, Clock clock) {
        this.props = props;
        this.repo = repo;
        this.jdbc = jdbc;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(props.health().timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedRateString = "${audit.workers.health-interval:PT60S}",
            initialDelayString = "${audit.workers.health-initial-delay:PT30S}")
    @SchedulerLock(name = "audit.health_check", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
    public void scheduled() {
        Map<String, String> result = run();
        log.debug("Health check: {}", result);
    }

    public Map<String, String> run() {
        Map<String, Probe> results = new LinkedHashMap<>();
        for (String service : props.health().services()) {
            Probe p;
            if ("postgresql".equals(service)) {
                p = pingPostgres();
            } else if ("redis".equals(service)) {
                p = pingRedis(props.health().redisUrl());
            } else if (props.health().urls().get(service) instanceof String url && !url.isBlank()) {
                p = pingHttp(url);
            } else {
                p = new Probe("UNKNOWN", null, Map.of("reason", "no URL configured"));
            }
            results.put(service, p);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<String, String> summary = new LinkedHashMap<>();
        results.forEach((s, p) -> {
            repo.insert(s, p.status(), p.responseTimeMs(), p.details(), now);
            summary.put(s, p.status());
        });
        return summary;
    }

    Probe pingHttp(String url) {
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(props.health().timeout()).GET().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return new Probe(resp.statusCode() < 400 ? "UP" : "DEGRADED", elapsed(start),
                    Map.of("http_status", resp.statusCode()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(start, e);
        } catch (Exception e) {
            return down(start, e);
        }
    }

    Probe pingPostgres() {
        long start = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new Probe("UP", elapsed(start), Map.of());
        } catch (Exception e) {
            return down(start, e);
        }
    }

    Probe pingRedis(String redisUrl) {
        if (redisUrl == null || redisUrl.isBlank()) {
            return new Probe("UNKNOWN", null, Map.of("reason", "no URL configured"));
        }
        long start = System.nanoTime();
        try {
            URI uri = URI.create(redisUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 6379;
            int timeout = (int) Math.min(Integer.MAX_VALUE, Math.min(props.health().timeout().toMillis(), 3000));
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(uri.getHost(), port), timeout);
                s.setSoTimeout(timeout);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                String userInfo = uri.getRawUserInfo();
                if (userInfo != null && !userInfo.isEmpty()) {
                    int colon = userInfo.indexOf(':');
                    String user = colon < 0 ? "" : URLDecoder.decode(userInfo.substring(0, colon), StandardCharsets.UTF_8);
                    String pass = URLDecoder.decode(colon < 0 ? userInfo : userInfo.substring(colon + 1),
                            StandardCharsets.UTF_8);
                    out.write(user.isEmpty() ? resp("AUTH", pass) : resp("AUTH", user, pass));
                    String auth = readLine(in);
                    if (!auth.startsWith("+OK")) {
                        return new Probe("DOWN", elapsed(start), Map.of("error", "AUTH refused"));
                    }
                }
                out.write(resp("PING"));
                out.flush();
                String line = readLine(in);
                if (line.startsWith("+PONG")) {
                    return new Probe("UP", elapsed(start), Map.of());
                }
                return new Probe("DOWN", elapsed(start), Map.of("error", truncate("unexpected reply: " + line)));
            }
        } catch (Exception e) {
            return down(start, e);
        }
    }

    private static byte[] resp(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n' && sb.length() < 512) {
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static Probe down(long start, Exception e) {
        String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return new Probe("DOWN", elapsed(start), Map.of("error", truncate(msg)));
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private static int elapsed(long start) {
        return (int) Duration.ofNanos(System.nanoTime() - start).toMillis();
    }
}
