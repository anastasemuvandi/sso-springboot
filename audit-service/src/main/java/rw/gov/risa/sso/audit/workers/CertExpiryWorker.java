package rw.gov.risa.sso.audit.workers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.Severity;
import rw.gov.risa.sso.audit.repository.CertExpiryRepository;
import rw.gov.risa.sso.audit.service.AuditWriter;
import rw.gov.risa.sso.audit.service.ReportService;

/**
 * Daily 06:00 Africa/Kigali: track every user's {@code certExpiry} and write one
 * {@code cert.expiry_warning} (WARNING) per threshold crossed — 90, 60, 30 days — once per
 * threshold (reference workers/cert_expiry.py). A user already past several thresholds on first
 * sight gets one event per threshold, as the reference.
 *
 * <p>Two reference bugs fixed: the tracking row is found by {@code user_id} (the reference looked
 * it up by primary key, so the second run hit the unique constraint), and a renewed certificate
 * (expiry date changed) resets the notified flags so the new certificate is warned about too.
 */
@Component
@ConditionalOnProperty(name = "audit.workers.enabled", havingValue = "true", matchIfMissing = true)
public class CertExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(CertExpiryWorker.class);
    public static final String EVENT = "cert.expiry_warning";

    public record Result(int processed, int warningsWritten) {
    }

    private final KeycloakUserSource keycloak;
    private final CertExpiryRepository tracking;
    private final AuditWriter writer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CertExpiryWorker(KeycloakUserSource keycloak, CertExpiryRepository tracking, AuditWriter writer,
                            TransactionTemplate tx, Clock clock) {
        this.keycloak = keycloak;
        this.tracking = tracking;
        this.writer = writer;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(cron = "${audit.workers.cert-expiry-cron:0 0 6 * * *}", zone = "Africa/Kigali")
    @SchedulerLock(name = "audit.cert_expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        if (!keycloak.configured()) {
            log.warn("Certificate-expiry check skipped: KEYCLOAK_ADMIN_PASSWORD is not set");
            return;
        }
        List<KeycloakUserSource.CertUser> users;
        try {
            users = keycloak.usersWithCertExpiry();
        } catch (RuntimeException e) {
            log.error("Certificate-expiry check could not read Keycloak users: {}", e.getClass().getSimpleName());
            return;
        }
        Result r = process(users);
        log.info("Certificate-expiry check: processed={} warnings_written={}", r.processed(), r.warningsWritten());
    }

    public Result process(List<KeycloakUserSource.CertUser> users) {
        LocalDate today = LocalDate.now(clock.withZone(ReportService.KIGALI));
        int warnings = 0;
        for (KeycloakUserSource.CertUser u : users) {
            LocalDate expiry;
            try {
                expiry = LocalDate.parse(u.certExpiry().strip());
            } catch (DateTimeParseException | NullPointerException e) {
                continue;
            }
            Integer n = tx.execute(status -> processOne(u, expiry, today));
            warnings += n == null ? 0 : n;
        }
        return new Result(users.size(), warnings);
    }

    private int processOne(KeycloakUserSource.CertUser u, LocalDate expiry, LocalDate today) {
        int days = (int) ChronoUnit.DAYS.between(today, expiry);
        var existing = tracking.findForUpdate(u.userId());
        boolean renewed = existing.isPresent() && !existing.get().certExpiry().equals(expiry);
        boolean n90 = existing.isPresent() && !renewed && existing.get().notified90();
        boolean n60 = existing.isPresent() && !renewed && existing.get().notified60();
        boolean n30 = existing.isPresent() && !renewed && existing.get().notified30();

        int written = 0;
        int[] thresholds = {90, 60, 30};
        boolean[] flags = {n90, n60, n30};
        for (int i = 0; i < thresholds.length; i++) {
            if (days <= thresholds[i] && !flags[i]) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("days_remaining", days);
                details.put("cert_expiry", expiry.toString());
                details.put("threshold", thresholds[i]);
                details.put("email", u.email());
                writer.append(AuditEventCreate.system(EVENT, u.userId(), null, null, null, details, Severity.WARNING));
                flags[i] = true;
                written++;
            }
        }
        tracking.save(new CertExpiryRepository.Tracking(u.userId(), u.employeeId(), u.email(), expiry, days,
                flags[2], flags[1], flags[0]));
        return written;
    }
}
