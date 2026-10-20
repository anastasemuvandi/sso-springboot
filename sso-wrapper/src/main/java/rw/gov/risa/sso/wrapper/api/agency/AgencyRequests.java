package rw.gov.risa.sso.wrapper.api.agency;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of {@code /agency/**} (reference {@code schema/agency.py}; camelCase wire names as there). */
public final class AgencyRequests {

    private AgencyRequests() {
    }

    @Schema(name = "AgencyCreate")
    public record AgencyCreate(
            @NotBlank @Size(max = 255) String name,
            List<String> realmRoles,
            Map<String, List<String>> clientRoles,
            Map<String, List<String>> attributes) {
    }

    @Schema(name = "AgencyUpdate")
    public record AgencyUpdate(
            @Size(min = 1, max = 255) String name,
            List<String> realmRoles,
            Map<String, List<String>> clientRoles,
            Map<String, List<String>> attributes) {
    }

    @Schema(name = "MemberAdd")
    public record MemberAdd(@NotBlank String userId) {
    }

    @Schema(name = "SubgroupCreate")
    public record SubgroupCreate(
            @NotBlank @Size(max = 255) String name,
            Map<String, List<String>> attributes) {
    }

    @Schema(name = "RoleAssignment")
    public record RoleAssignment(List<String> realmRoles, Map<String, List<String>> clientRoles) {
    }

    @Schema(name = "RoleRemoval")
    public record RoleRemoval(List<String> realmRoles, Map<String, List<String>> clientRoles) {
    }
}
