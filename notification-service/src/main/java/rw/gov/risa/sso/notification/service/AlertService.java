package rw.gov.risa.sso.notification.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import rw.gov.risa.sso.notification.api.AlertRead;
import rw.gov.risa.sso.notification.detector.Severity;
import rw.gov.risa.sso.notification.repository.AlertRepository;
import rw.gov.risa.sso.notification.web.ApiException;

@Service
public class AlertService {

    private final AlertRepository repo;
    private final Clock clock;

    public AlertService(AlertRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public List<AlertRead> list(String severity, Boolean acknowledged, String ruleName, int limit, int offset) {
        // Reference: severity.upper(); an unknown severity simply matches nothing.
        String sev = severity == null || severity.isBlank() ? null
                : Severity.parse(severity).map(Enum::name).orElse(severity.toUpperCase());
        String rule = ruleName == null || ruleName.isBlank() ? null : ruleName;
        return repo.list(sev, acknowledged, rule, limit, offset).stream().map(AlertRead::of).toList();
    }

    public AlertRead get(UUID id) {
        return repo.findById(id).map(AlertRead::of).orElseThrow(() -> ApiException.notFound("Alert not found"));
    }

    public AlertRead acknowledge(UUID id, String by) {
        return repo.acknowledge(id, by, clock.instant()).map(AlertRead::of)
                .orElseThrow(() -> ApiException.notFound("Alert not found"));
    }
}
