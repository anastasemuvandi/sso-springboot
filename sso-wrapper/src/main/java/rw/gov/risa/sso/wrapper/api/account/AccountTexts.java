package rw.gov.risa.sso.wrapper.api.account;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The account pages' strings, from {@code i18n/account.properties} (English) and
 * {@code i18n/account_rw.properties} (Kinyarwanda — reuse the sign-in themes' vocabulary; have a native
 * speaker review before production, as the reference notes). Read as UTF-8; the JVM's default locale is
 * never consulted.
 */
final class AccountTexts {

    static final String EN = "en";
    static final String RW = "rw";

    private static final String BUNDLE = "i18n.account";
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private AccountTexts() {
    }

    /** {@code rw} when the value starts with "rw" (case-insensitive), else {@code en}. */
    static String lang(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(RW) ? RW : EN;
    }

    static Map<String, String> of(String lang) {
        return CACHE.computeIfAbsent(lang, AccountTexts::load);
    }

    private static Map<String, String> load(String lang) {
        Locale locale = RW.equals(lang) ? Locale.of(RW) : Locale.ROOT;
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : Collections.list(bundle.getKeys())) {
            out.put(key, bundle.getString(key));
        }
        return Collections.unmodifiableMap(out);
    }
}
