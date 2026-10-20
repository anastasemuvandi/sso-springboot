package rw.gov.risa.sso.wrapper.service.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.error.ApiException;

/**
 * {@code GET /saml/metadata} (port of {@code api/saml.py}): Keycloak's SAML IdP descriptor at a stable wrapper
 * URL. Off unless ENABLE_SAML (404, so the surface is not accidentally exposed). SAML SSO/SLO themselves are
 * served by Keycloak; the wrapper does not proxy them.
 */
@Service
public class SamlGatewayService {

    private static final Logger log = LoggerFactory.getLogger(SamlGatewayService.class);

    private final GatewayProperties props;
    private final KeycloakOidc keycloak;

    public SamlGatewayService(GatewayProperties props, KeycloakOidc keycloak) {
        this.props = props;
        this.keycloak = keycloak;
    }

    public byte[] metadata() {
        if (!props.enableSaml()) {
            throw new ApiException(HttpStatus.NOT_FOUND, GatewayErrors.SAML_DISABLED,
                    "SAML support is disabled. Set ENABLE_SAML=True to enable.");
        }
        KeycloakOidc.Answer a;
        try {
            a = keycloak.samlDescriptor();
        } catch (ApiException e) {
            throw ApiException.badGateway(rw.gov.risa.sso.wrapper.error.ErrorCodes.KEYCLOAK_ERROR,
                    "Could not retrieve SAML metadata from Keycloak.");
        }
        if (!a.ok()) {
            log.error("gateway: SAML descriptor fetch failed with HTTP {}", a.status());
            throw ApiException.badGateway(rw.gov.risa.sso.wrapper.error.ErrorCodes.KEYCLOAK_ERROR,
                    "Could not retrieve SAML metadata from Keycloak.");
        }
        return a.raw();
    }
}
