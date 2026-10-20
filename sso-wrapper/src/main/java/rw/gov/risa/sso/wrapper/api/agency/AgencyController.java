package rw.gov.risa.sso.wrapper.api.agency;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.api.agency.AgencyRequests.AgencyCreate;
import rw.gov.risa.sso.wrapper.api.agency.AgencyRequests.AgencyUpdate;
import rw.gov.risa.sso.wrapper.api.agency.AgencyRequests.MemberAdd;
import rw.gov.risa.sso.wrapper.api.agency.AgencyRequests.RoleAssignment;
import rw.gov.risa.sso.wrapper.api.agency.AgencyRequests.RoleRemoval;
import rw.gov.risa.sso.wrapper.api.agency.AgencyRequests.SubgroupCreate;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService.AgencyView;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService.MemberView;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService.SubgroupInfo;

/**
 * Agencies = top-level Keycloak groups; departments = their children (read from
 * {@code /groups/{id}/children}). Platform admins only (admin-tier gate in {@code RouteRules}).
 */
@RestController
@Validated
@RequestMapping("/agency")
@Tag(name = "Agency Management")
public class AgencyController {

    private final AgencyAdminService agencies;

    public AgencyController(AgencyAdminService agencies) {
        this.agencies = agencies;
    }

    @GetMapping("/")
    @Operation(summary = "Every agency with members, roles, clients and departments")
    public List<AgencyView> list() {
        return agencies.list();
    }

    @PostMapping("/")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an agency (top-level group) with optional roles and attributes")
    public AgencyView create(@AuthenticationPrincipal CallerPrincipal caller, @Valid @RequestBody AgencyCreate b) {
        return agencies.create(b.name(), b.realmRoles(), b.clientRoles(), b.attributes(), caller);
    }

    @GetMapping("/count")
    @Operation(summary = "Number of agencies")
    public Map<String, Object> count() {
        return agencies.count();
    }

    @GetMapping("/metrics")
    @Operation(summary = "System-wide metrics (users in several agencies counted once)")
    public Map<String, Object> metrics() {
        return agencies.metrics();
    }

    @GetMapping("/{group_id}")
    @Operation(summary = "One agency with complete details")
    public AgencyView get(@PathVariable("group_id") String groupId) {
        return agencies.get(groupId);
    }

    @PutMapping("/{group_id}")
    @Operation(summary = "Update an agency; realmRoles / clientRoles, when sent, replace the current ones")
    public AgencyView update(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("group_id") String groupId,
                             @Valid @RequestBody AgencyUpdate b) {
        return agencies.update(groupId, b.name(), b.realmRoles(), b.clientRoles(), b.attributes(), caller);
    }

    @DeleteMapping("/{group_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an agency (its users are not deleted)")
    public void delete(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("group_id") String groupId) {
        agencies.delete(groupId, caller);
    }

    @GetMapping("/{group_id}/subgroups")
    @Operation(summary = "The agency's departments (direct children)")
    public List<SubgroupInfo> subgroups(@PathVariable("group_id") String groupId) {
        return agencies.subgroups(groupId);
    }

    @PostMapping("/{group_id}/subgroups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a department under the agency")
    public SubgroupInfo createSubgroup(@AuthenticationPrincipal CallerPrincipal caller,
                                       @PathVariable("group_id") String groupId, @Valid @RequestBody SubgroupCreate b) {
        return agencies.createSubgroup(groupId, b.name(), b.attributes(), caller);
    }

    @GetMapping("/{group_id}/members")
    @Operation(summary = "The agency's direct members")
    public List<MemberView> members(@PathVariable("group_id") String groupId) {
        return agencies.members(groupId);
    }

    @PostMapping("/{group_id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Add a user to the agency")
    public void addMember(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("group_id") String groupId,
                          @Valid @RequestBody MemberAdd b) {
        agencies.addMember(groupId, b.userId(), caller);
    }

    @DeleteMapping("/{group_id}/members/{user_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a user from the agency")
    public void removeMember(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("group_id") String groupId,
                             @PathVariable("user_id") String userId) {
        agencies.removeMember(groupId, userId, caller);
    }

    @PostMapping("/{group_id}/roles")
    @Operation(summary = "Assign realm / client roles to the agency (additive)")
    public AgencyView addRoles(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("group_id") String groupId,
                               @Valid @RequestBody RoleAssignment b) {
        return agencies.addRoles(groupId, b.realmRoles(), b.clientRoles(), caller);
    }

    @DeleteMapping("/{group_id}/roles")
    @Operation(summary = "Remove realm / client roles from the agency")
    public AgencyView removeRoles(@AuthenticationPrincipal CallerPrincipal caller,
                                  @PathVariable("group_id") String groupId, @Valid @RequestBody RoleRemoval b) {
        return agencies.removeRoles(groupId, b.realmRoles(), b.clientRoles(), caller);
    }
}
