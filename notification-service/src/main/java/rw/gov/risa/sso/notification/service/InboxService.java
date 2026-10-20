package rw.gov.risa.sso.notification.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import rw.gov.risa.sso.notification.api.NotificationRead;
import rw.gov.risa.sso.notification.repository.NotificationRepository;
import rw.gov.risa.sso.notification.web.ApiException;

/** {@code /notifications}: list, mark one read, mark all read for a recipient. */
@Service
public class InboxService {

    private final NotificationRepository repo;

    public InboxService(NotificationRepository repo) {
        this.repo = repo;
    }

    public List<NotificationRead> list(String recipient, Boolean isRead, String channel, UUID alertId,
                                       int limit, int offset) {
        return repo.list(blank(recipient), isRead, blank(channel), alertId, limit, offset).stream()
                .map(NotificationRead::of).toList();
    }

    public NotificationRead markRead(UUID id) {
        return repo.markRead(id).map(NotificationRead::of)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
    }

    public int markAllRead(String recipient) {
        return repo.markAllRead(recipient);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
