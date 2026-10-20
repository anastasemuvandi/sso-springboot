package rw.gov.risa.sso.audit.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import rw.gov.risa.sso.audit.api.dto.Reports;
import rw.gov.risa.sso.audit.api.dto.ServiceHealthRead;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.repository.CertExpiryRepository;
import rw.gov.risa.sso.audit.repository.ServiceHealthRepository;

/** Health history and the JSON reports (reference routes/health.py). */
@Service
public class ReportService {

    public static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final ServiceHealthRepository health;
    private final CertExpiryRepository certs;
    private final AuditEventRepository events;
    private final Clock clock;

    public ReportService(ServiceHealthRepository health, CertExpiryRepository certs, AuditEventRepository events,
                         Clock clock) {
        this.health = health;
        this.certs = certs;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ServiceHealthRead> latestHealth(int limit) {
        return health.latest(limit);
    }

    @Transactional(readOnly = true)
    public List<ServiceHealthRead> healthHistory(String service, int limit) {
        return health.history(service, limit);
    }

    @Transactional(readOnly = true)
    public Reports.CertExpiryReport certExpiry(int daysAhead) {
        LocalDate cutoff = LocalDate.now(clock.withZone(KIGALI)).plusDays(daysAhead);
        List<Reports.CertExpiryItem> items = certs.expiringOnOrBefore(cutoff).stream()
                .map(t -> new Reports.CertExpiryItem(t.userId(), t.employeeId(), t.email(),
                        t.certExpiry().toString(), t.daysRemaining()))
                .toList();
        return new Reports.CertExpiryReport(daysAhead, items.size(), items);
    }

    /**
     * Top 10 apps by {@code authz.access.granted}, failed logins per UTC day (newest 30 days with
     * any), and the access-denial count. Unlike the reference, the denial count honours the window
     * too (the reference counted all time, which made the three figures incomparable).
     */
    @Transactional(readOnly = true)
    public Reports.AccessAnalytics accessAnalytics(OffsetDateTime from, OffsetDateTime to) {
        List<Reports.AppCount> top = events.topApps(from, to, 10).stream()
                .map(e -> new Reports.AppCount(e.getKey(), e.getValue())).toList();
        List<Reports.DayCount> trend = events.failedLoginsByDay(from, to, 30).stream()
                .map(e -> new Reports.DayCount(e.getKey(), e.getValue())).toList();
        return new Reports.AccessAnalytics(top, trend, events.countType("authz.access.denied", from, to));
    }
}
