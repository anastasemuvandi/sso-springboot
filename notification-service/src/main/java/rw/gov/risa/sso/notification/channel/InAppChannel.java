package rw.gov.risa.sso.notification.channel;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * In-app: one {@code notifications} row per admin recipient (reference {@code write_inapp}),
 * read through {@code GET /notifications}. With no recipients configured the reference wrote
 * nothing; here a single {@code skipped} row records that.
 */
@Component
public class InAppChannel implements NotificationChannel {

    @Override
    public String name() {
        return "inapp";
    }

    @Override
    public List<Delivery> deliver(AlertMessage alert, List<String> adminEmails) {
        if (adminEmails.isEmpty()) {
            return List.of(Delivery.skipped(name(), "", "no in-app recipients configured (ADMIN_EMAILS is empty)"));
        }
        return adminEmails.stream().map(r -> Delivery.sent(name(), r)).toList();
    }
}
