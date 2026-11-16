package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import com.reconflow.paymentreconflow.shared.context.Role;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/members}: the caller's organization's members (authentication.plan §4.6). The
 * organization comes from TenantContext, never from the path or the body. Request records are
 * separate from the entities, so a client can't set {@code platformAdmin} or an organization.
 */
@RestController
@RequestMapping("/api/v1/members")
class MemberController {

    record CreateMemberRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull Role role) {}

    record ChangeRoleRequest(@NotNull Role role) {}

    record CreatedMemberResponse(
            UUID userId,
            String email,
            String displayName,
            Role role,
            UserStatus status,
            java.time.Instant createdAt,
            @Nullable String temporaryPassword) {}

    record TemporaryPasswordResponse(String temporaryPassword) {}

    private final MemberService members;

    MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    @ApiResponses({@ApiResponse(responseCode = "200", description = "A page of members")})
    @RequiresPermission(Permission.MEMBER_MANAGE)
    PagedModel<MemberView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) @Nullable List<String> sort) {
        return new PagedModel<>(members.list(page, size, sort));
    }

    /**
     * 201 with the member. {@code temporaryPassword} is present only when a new account was
     * created, shown this once, and never logged; it's null when an existing user was attached.
     */
    @PostMapping
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "The member, with a one-time temporary password"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)"),
        @ApiResponse(responseCode = "503", description = "a dependency is unavailable; retry later")
    })
    @RequiresPermission(Permission.MEMBER_MANAGE)
    ResponseEntity<CreatedMemberResponse> create(@Valid @RequestBody CreateMemberRequest request) {
        var created = members.create(request.email(), request.displayName(), request.role());
        MemberView m = created.member();
        return ResponseEntity.created(URI.create("/api/v1/members/" + m.userId()))
                .body(new CreatedMemberResponse(
                        m.userId(),
                        m.email(),
                        m.displayName(),
                        m.role(),
                        m.status(),
                        m.createdAt(),
                        created.temporaryPassword()));
    }

    @PatchMapping(
            path = "/{userId}",
            consumes = {"application/json", "application/merge-patch+json"})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The updated member"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)")
    })
    @RequiresPermission(Permission.MEMBER_MANAGE)
    MemberView changeRole(@PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest request) {
        return members.changeRole(userId, request.role());
    }

    /** The only account-recovery path in v1: there is no email, so no self-service reset. */
    @PostMapping("/{userId}/reset-password")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A new temporary password"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)"),
        @ApiResponse(responseCode = "503", description = "a dependency is unavailable; retry later")
    })
    @RequiresPermission(Permission.MEMBER_MANAGE)
    TemporaryPasswordResponse resetPassword(@PathVariable UUID userId) {
        return new TemporaryPasswordResponse(members.resetPassword(userId));
    }

    @PostMapping("/{userId}/disable")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The disabled member"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)"),
        @ApiResponse(responseCode = "503", description = "a dependency is unavailable; retry later")
    })
    @RequiresPermission(Permission.MEMBER_MANAGE)
    MemberView disable(@PathVariable UUID userId) {
        return members.disable(userId);
    }
}
