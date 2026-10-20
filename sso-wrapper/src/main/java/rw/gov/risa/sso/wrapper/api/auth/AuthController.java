package rw.gov.risa.sso.wrapper.api.auth;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.gateway.AuthGatewayService;
import rw.gov.risa.sso.wrapper.service.gateway.AuthGatewayService.LoginResult;
import rw.gov.risa.sso.wrapper.service.gateway.AuthGatewayService.UserInfo;
import rw.gov.risa.sso.wrapper.service.gateway.ClientAuthenticator;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/** {@code /auth/*} (spec §5.1). Thin: bind, validate, call {@link AuthGatewayService}. */
@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthGatewayService auth;

    public AuthController(AuthGatewayService auth) {
        this.auth = auth;
    }

    /**
     * {@code LoginRequest}. {@code client_secret} is required for a confidential client (or HTTP Basic);
     * the wrapper never supplies it on the caller's behalf.
     */
    public record LoginRequest(
            @NotBlank @Size(max = 255) @JsonProperty("username") String username,
            @NotBlank @Size(max = 256) @JsonProperty("password") String password,
            @NotBlank @Size(max = 255) @JsonProperty("client_id") String clientId,
            @Size(max = 512) @JsonProperty("client_secret") String clientSecret) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", clientId=" + clientId + "]";
        }
    }

    /** {@code TokenResponse}. */
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("redirect_url") String redirectUrl) {
    }

    @PostMapping("/login")
    @RateLimit(value = "10/minute", key = RateLimit.Key.IP)
    @Operation(summary = "Password login through the access, join-key and MFA gates")
    public TokenResponse login(@Valid @RequestBody LoginRequest body,
                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        ClientAuthenticator.Presented presented =
                ClientAuthenticator.presented(authorization, body.clientId(), body.clientSecret());
        LoginResult r = auth.login(body.username(), body.password(), presented, RequestInfo.clientIp());
        return new TokenResponse(r.accessToken(), r.refreshToken(), r.idToken(), r.tokenType(), r.redirectUrl());
    }

    @PostMapping("/logout")
    @Operation(summary = "End the SSO session behind the bearer token")
    public Map<String, String> logout(@AuthenticationPrincipal CallerPrincipal caller) {
        return auth.logout(caller, RequestInfo.clientIp());
    }

    @GetMapping("/verify")
    @Operation(summary = "Is this bearer valid and its session live? Fails closed")
    public Map<String, Boolean> verify(@AuthenticationPrincipal CallerPrincipal caller) {
        if (caller == null) {
            throw ApiException.unauthorized("Invalid token");
        }
        return Map.of("valid", auth.verify(caller));
    }

    @GetMapping("/userinfo")
    @Operation(summary = "The caller's identity, roles, agencies and clients")
    public UserInfo userinfo(@AuthenticationPrincipal CallerPrincipal caller) {
        if (caller == null) {
            throw ApiException.unauthorized("Invalid token");
        }
        return auth.userInfo(caller);
    }
}
