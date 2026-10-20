package rw.gov.risa.sso.wrapper.service.gateway;

import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;

/** Gateway-only machine codes (RFC 6749 §4.1.2.1 / §5.2 names) and factories. */
public final class GatewayErrors {
    private GatewayErrors() {
    }

    public static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    public static final String UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";
    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String SAML_DISABLED = "saml_disabled";

    public static ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, message);
    }

    public static ApiException invalidGrant(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_GRANT, message);
    }

    public static ApiException invalidClient(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_CLIENT, message);
    }

    public static ApiException invalidToken(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_TOKEN, message);
    }

    public static ApiException upstream(String message) {
        return ApiException.unavailable(ErrorCodes.UPSTREAM_UNAVAILABLE, message);
    }
}
