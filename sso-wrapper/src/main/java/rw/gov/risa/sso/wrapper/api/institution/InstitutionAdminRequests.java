package rw.gov.risa.sso.wrapper.api.institution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of {@code /institution-admin/*} (field names as the reference's pydantic models). */
public final class InstitutionAdminRequests {

    private InstitutionAdminRequests() {
    }

    @Schema(name = "CreateUserIn")
    public record CreateUserIn(
            @NotBlank @Size(min = 3, max = 255) String username,
            @NotBlank @Email String email,
            @JsonProperty("firstName") @NotBlank @Size(min = 1, max = 255) String firstName,
            @JsonProperty("lastName") @NotBlank @Size(min = 1, max = 255) String lastName,
            Boolean enabled,
            @JsonProperty("emailVerified") Boolean emailVerified,
            @JsonProperty("national_id") @Schema(description = "National ID (16 digits); stored as the national_id "
                    + "attribute and carried in the join_key claim for clients keyed on NID") String nationalId,
            @Schema(description = "Phone number (9-15 digits, optional leading '+'); stored as the phone attribute")
            String phone) {
    }

    @Schema(name = "UpdateUserIn")
    public record UpdateUserIn(
            @Email String email,
            @JsonProperty("firstName") @Size(min = 1, max = 255) String firstName,
            @JsonProperty("lastName") @Size(min = 1, max = 255) String lastName,
            Boolean enabled,
            @JsonProperty("emailVerified") Boolean emailVerified,
            @JsonProperty("national_id") String nationalId,
            String phone) {
    }

    @Schema(name = "RoleIn")
    public record RoleIn(@NotBlank @Schema(description = "Realm role name; must be permitted by your map-roles "
            + "scoping (see GET /institution-admin/roles)") String role) {
    }

    /**
     * A new application for the caller's OWN institution. There is no institution field, and that is
     * the contract: any field other than these three (an {@code institution}, {@code owner_institution},
     * {@code cross_cutting}, a full {@code client_id}, ...) is refused with 400.
     */
    @Schema(name = "CreateAppIn")
    public static final class CreateAppIn {
        @NotBlank
        @Size(min = 2, max = 64)
        @Schema(description = "The app part of the client-id only. The client is created as "
                + "<your institution>-<app> — send 'portal', not 'minagri-portal'.")
        private String app;

        @NotEmpty
        @JsonProperty("redirect_uris")
        @Schema(description = "Valid redirect URIs. Exact match; a trailing slash matters.")
        private List<String> redirectUris;

        @JsonProperty("post_logout_redirect_uris")
        @Schema(description = "Keycloak's ##-separated list. + reuses the redirect URIs; omit for none.")
        private String postLogoutRedirectUris;

        @JsonIgnore
        private final Map<String, Object> unexpected = new LinkedHashMap<>();

        public String getApp() {
            return app;
        }

        public void setApp(String app) {
            this.app = app;
        }

        public List<String> getRedirectUris() {
            return redirectUris;
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = redirectUris;
        }

        public String getPostLogoutRedirectUris() {
            return postLogoutRedirectUris;
        }

        public void setPostLogoutRedirectUris(String postLogoutRedirectUris) {
            this.postLogoutRedirectUris = postLogoutRedirectUris;
        }

        @JsonAnySetter
        public void unexpected(String name, Object value) {
            unexpected.put(name, value);
        }

        @JsonIgnore
        public Map<String, Object> unexpectedFields() {
            return unexpected;
        }
    }

    @Schema(name = "JoinColumnRequest")
    public record JoinColumnRequest(@JsonProperty("join_column") @NotBlank String joinColumn, Boolean confirm) {
        public boolean confirmed() {
            return Boolean.TRUE.equals(confirm);
        }
    }

    @Schema(name = "AppMfaRequest")
    public record AppMfaRequest(@NotNull @Schema(description = "Must be true: an institution admin can only turn "
            + "TOTP on") Boolean required) {
    }

    @Schema(name = "UsersRequest")
    public record UsersRequest(@JsonProperty("user_ids") @NotNull @Size(min = 1, max = 500) List<String> userIds) {
    }

    @Schema(name = "RevokeRequest")
    public record RevokeRequest(
            @JsonProperty("user_ids") @NotNull @Size(min = 1, max = 500) List<String> userIds,
            @JsonProperty("client_ids") List<String> clientIds,
            @JsonProperty("all_clients") Boolean allClients,
            @JsonProperty("confirm_all_clients") Boolean confirmAllClients,
            @JsonAlias("dry_run") @Schema(description = "Compute the report and change nothing (alias dry_run)")
            Boolean preview) {
    }
}
