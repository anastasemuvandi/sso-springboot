package rw.gov.risa.sso.wrapper.service.accessmatrix;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The access matrix's spreadsheet round-trip (spec §5.14).
 *
 * @param signingKey ACCESS_MATRIX_SIGNING_KEY (or {@code _FILE}): the HMAC key of the export's signed frame.
 *                   Unset → export/import answer 503 {@code not_configured} (staging/prod refuse to start
 *                   without it — {@code RequiredSecretsValidator}).
 */
@ConfigurationProperties(prefix = "sso.access-matrix")
public record AccessMatrixProperties(@DefaultValue("") String signingKey) {

    public AccessMatrixProperties {
        signingKey = signingKey == null ? "" : signingKey;
    }

    @Override
    public String toString() {
        return "AccessMatrixProperties[signingKey=" + (signingKey.isBlank() ? "unset" : "<set>") + "]";
    }
}
