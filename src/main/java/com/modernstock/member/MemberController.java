package com.modernstock.member;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.member.InvitationService.InvitationResponse;
import com.modernstock.member.InvitationService.InviteRequest;
import com.modernstock.member.MemberService.LinkResponse;
import com.modernstock.member.MemberService.MemberResponse;
import com.modernstock.member.MemberService.RoleResponse;
import com.modernstock.member.MemberService.UpdateMemberRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Members")
@RestController
@RequiredArgsConstructor
public class MemberController {

    private final MemberService members;
    private final InvitationService invitations;

    @Operation(summary = "Roles you can give", description = "The four built-in roles, then this business's custom roles, with their permissions.")
    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return members.roles();
    }

    @GetMapping("/members")
    @PreAuthorize("hasAuthority('member:view')")
    public List<MemberResponse> listMembers() {
        return members.list();
    }

    @Operation(summary = "Change a member's role, branch or status",
            description = "Takes effect on the member's next request. The last active Owner can't be changed or disabled.")
    @PatchMapping("/members/{id}")
    @PreAuthorize("hasAuthority('member:manage')")
    public MemberResponse updateMember(@PathVariable Long id, @Valid @RequestBody UpdateMemberRequest req) {
        return members.update(id, req);
    }

    @Operation(summary = "Remove a member", description = "The name stays on old documents.")
    @DeleteMapping("/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('member:manage')")
    public void removeMember(@PathVariable Long id) {
        members.remove(id);
    }

    @Operation(summary = "Password reset link for a member without email", description = "Valid 24 hours. Send it by Telegram or show it as a QR code.")
    @PostMapping("/members/{id}/reset-link")
    @PreAuthorize("hasAuthority('member:manage')")
    public LinkResponse resetLink(@PathVariable Long id) {
        return members.resetLink(id);
    }

    @GetMapping("/members/invitations")
    @PreAuthorize("hasAuthority('member:manage')")
    public List<InvitationResponse> listInvitations() {
        return invitations.list();
    }

    @Operation(summary = "Invite someone", description = "Returns the one-time link (valid 7 days). Checks the plan's member limit; "
            + "pending invitations count as members.")
    @PostMapping("/members/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('member:manage')")
    public InvitationResponse invite(@Valid @RequestBody InviteRequest req) {
        return invitations.create(req);
    }

    @Operation(summary = "New link for a pending or expired invitation", description = "The old link stops working.")
    @PostMapping("/members/invitations/{id}/resend")
    @PreAuthorize("hasAuthority('member:manage')")
    public InvitationResponse resendInvitation(@PathVariable Long id) {
        return invitations.resend(id);
    }

    @DeleteMapping("/members/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('member:manage')")
    public void cancelInvitation(@PathVariable Long id) {
        invitations.cancel(id);
    }
}
