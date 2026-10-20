package rw.gov.risa.sso.wrapper.api.users;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of {@code /users/**} (reference {@code schema/user.py}; camelCase wire names as there). */
public final class UserRequests {

    private UserRequests() {
    }

    @Schema(name = "CreateUserRequest")
    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank String email,
            @NotBlank String firstName,
            @NotBlank String lastName,
            Boolean enabled,
            Boolean emailVerified,
            Map<String, List<String>> attributes) {
    }

    @Schema(name = "UpdateUserRequest")
    public record UpdateUserRequest(
            String email,
            String firstName,
            String lastName,
            Boolean enabled,
            Boolean emailVerified,
            Map<String, List<String>> attributes) {
    }

    @Schema(name = "ResetPasswordRequest")
    public record ResetPasswordRequest(
            String type,
            @NotNull @Size(min = 8) String value,
            Boolean temporary) {
        @Override
        public String toString() {
            // Never print the password.
            return "ResetPasswordRequest[temporary=" + temporary + "]";
        }
    }

    @Schema(name = "BulkGroupAssignmentRequest")
    public record BulkGroupAssignmentRequest(
            @JsonProperty("group_ids") @NotEmpty List<@NotBlank String> groupIds) {
    }

    @Schema(name = "CompleteUserProvisioningRequest")
    public record CompleteUserProvisioningRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank String email,
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotNull @Size(min = 8) String password,
            Boolean temporary,
            @JsonProperty("group_ids") @NotEmpty List<@NotBlank String> groupIds,
            Boolean enabled,
            Boolean emailVerified,
            Map<String, List<String>> attributes) {
        @Override
        public String toString() {
            return "CompleteUserProvisioningRequest[username=" + username + "]";
        }
    }
}
