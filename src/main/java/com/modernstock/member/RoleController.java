package com.modernstock.member;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.member.MemberService.RoleResponse;
import com.modernstock.member.RoleService.PermissionResponse;
import com.modernstock.member.RoleService.RoleRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Custom roles (FR-6.24); the list of roles is GET /roles in MemberController. */
@Tag(name = "Members")
@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roles;

    @Operation(summary = "Every permission", description = "To tick in a custom role. ownerOnly ones can't be given to one.")
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('member:view')")
    public List<PermissionResponse> listPermissions() {
        return roles.permissions();
    }

    @Operation(summary = "Create a custom role", description = "Up to 10 per business. To duplicate a role, send its permissions.")
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('member:manage')")
    public RoleResponse createRole(@Valid @RequestBody RoleRequest req) {
        return roles.create(req);
    }

    @Operation(summary = "Change a custom role", description = "Its members get the new permissions on their next request.")
    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('member:manage')")
    public RoleResponse updateRole(@PathVariable Long id, @Valid @RequestBody RoleRequest req) {
        return roles.update(id, req);
    }

    @Operation(summary = "Delete a custom role", description = "422 ROLE_IN_USE while members or pending invitations have it.")
    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('member:manage')")
    public void deleteRole(@PathVariable Long id) {
        roles.delete(id);
    }
}
