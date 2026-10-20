package rw.gov.risa.sso.notification.service;

import rw.gov.risa.sso.notification.config.NotificationProperties;

public final class TestProps {
    private TestProps() {
    }

    public static NotificationProperties props(String admins, String smtpHost, String webhook) {
        return new NotificationProperties("key", false, admins, webhook, 2, 6, 22, 5, 60,
                new NotificationProperties.Audit("http://audit", "akey", 1000, 20, 5),
                new NotificationProperties.Smtp(smtpHost, 25, "", "", "sso-alerts@gov.rw", false, 5),
                new NotificationProperties.Detector(true, 60000, 60, 2, 2),
                new NotificationProperties.Report(true, "0 0 8 * * *", "Africa/Kigali"));
    }
}
