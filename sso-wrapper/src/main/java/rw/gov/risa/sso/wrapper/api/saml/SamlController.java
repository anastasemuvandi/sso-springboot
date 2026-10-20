package rw.gov.risa.sso.wrapper.api.saml;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.service.gateway.SamlGatewayService;

/** {@code /saml/*}: off (404) unless ENABLE_SAML. */
@RestController
@RequestMapping("/saml")
@Tag(name = "SAML")
public class SamlController {

    static final MediaType SAML_METADATA = MediaType.parseMediaType("application/samlmetadata+xml");

    private final SamlGatewayService saml;

    public SamlController(SamlGatewayService saml) {
        this.saml = saml;
    }

    @GetMapping("/metadata")
    @Operation(summary = "Keycloak's SAML IdP descriptor (ENABLE_SAML)")
    public ResponseEntity<byte[]> metadata() {
        return ResponseEntity.ok().contentType(SAML_METADATA).cacheControl(CacheControl.noStore())
                .body(saml.metadata());
    }
}
