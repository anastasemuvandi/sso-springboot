package rw.gov.risa.sso.wrapper.api.client;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of {@code /client/clients/**} (reference {@code schema/client.py}; wire names copied). */
public final class ClientRequests {

    private ClientRequests() {
    }

    @Schema(name = "ClientCreateRequest")
    public record ClientCreateRequest(
            @JsonProperty("client_name") @NotBlank @Size(max = 255) String clientName,
            @JsonProperty("redirect_uris") @NotNull List<@NotBlank String> redirectUris,
            @JsonProperty("post_logout_redirect_uris") String postLogoutRedirectUris,
            @JsonProperty("protocol") String protocol,
            @JsonProperty("cross_cutting") Boolean crossCutting,
            @JsonProperty("institutions") List<String> institutions,
            @JsonProperty("owner_institution") String ownerInstitution) {
    }

    @Schema(name = "ClientUpdateRequest")
    public record ClientUpdateRequest(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("description") String description,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("implicitFlowEnabled") Boolean implicitFlowEnabled,
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("public_client") Boolean publicClient,
            @JsonProperty("protocol") String protocol,
            @JsonProperty("client_authenticator_type") String clientAuthenticatorType,
            @JsonProperty("standard_flow_enabled") Boolean standardFlowEnabled,
            @JsonProperty("direct_access_grants_enabled") Boolean directAccessGrantsEnabled,
            @JsonProperty("service_accounts_enabled") Boolean serviceAccountsEnabled,
            @JsonProperty("attributes") Map<String, String> attributes) {
    }

    @Schema(name = "RedirectUrisUpdateRequest")
    public record RedirectUrisUpdateRequest(
            @JsonProperty("redirect_uris") @NotNull List<@NotBlank String> redirectUris) {
    }

    @Schema(name = "PostLogoutRedirectUrisUpdateRequest")
    public record PostLogoutRedirectUrisUpdateRequest(
            @JsonProperty("post_logout_redirect_uris") @NotNull List<String> postLogoutRedirectUris) {
    }

    @Schema(name = "ClientScopeRequest")
    public record ClientScopeRequest(
            @JsonProperty("cross_cutting") Boolean crossCutting,
            @JsonProperty("institutions") List<String> institutions,
            @JsonProperty("owner_institution") String ownerInstitution,
            @JsonProperty("confirm") Boolean confirm) {
    }

    @Schema(name = "JoinColumnRequest")
    public record JoinColumnRequest(
            @JsonProperty("join_column") @NotBlank String joinColumn,
            @JsonProperty("confirm") Boolean confirm) {
    }

    @Schema(name = "ClientMfaRequest")
    public record ClientMfaRequest(
            @JsonProperty("required") @NotNull Boolean required) {
    }
}
