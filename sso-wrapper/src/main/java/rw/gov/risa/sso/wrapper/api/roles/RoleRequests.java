package rw.gov.risa.sso.wrapper.api.roles;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of {@code /roles/**} (reference {@code api/roles.py}). */
public final class RoleRequests {

    private RoleRequests() {
    }

    @Schema(name = "RoleRequest")
    public record RoleRequest(
            @NotBlank @Size(min = 1, max = 50) String name,
            @Size(max = 255) String description,
            Boolean composite,
            Map<String, List<String>> attributes) {
    }

    /**
     * {@code roles} are role objects as Keycloak returns them; only {@code name} is read (the wrapper
     * resolves the role itself). {@code user_id} is accepted for compatibility; the path decides.
     */
    @Schema(name = "UserRoleAssignmentRequest")
    public record UserRoleAssignmentRequest(
            @JsonProperty("user_id") String userId,
            @NotEmpty List<@NotNull Map<String, Object>> roles) {

        public List<String> roleNames() {
            return roles.stream().map(r -> r.get("name")).filter(n -> n != null && !n.toString().isBlank())
                    .map(Object::toString).toList();
        }
    }
}
