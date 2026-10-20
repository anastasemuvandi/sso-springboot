package rw.gov.risa.sso.notification.channel;

import java.util.List;

public interface NotificationChannel {

    /** Wire name: {@code email}, {@code inapp} or {@code webhook}. */
    String name();

    /** Deliver; never throws — failures come back as {@link Delivery#FAILED}. */
    List<Delivery> deliver(AlertMessage alert, List<String> adminEmails);
}
