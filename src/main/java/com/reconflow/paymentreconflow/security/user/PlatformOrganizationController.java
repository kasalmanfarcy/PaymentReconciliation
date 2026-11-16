package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.organization.Organization;
import com.reconflow.paymentreconflow.organization.OrganizationStatus;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/organizations}. Guarded twice: the URL rule and the annotation both
 * require PLATFORM_ORG_MANAGE, which only TenantContextFilter grants, from the database flag.
 */
@RestController
@RequestMapping("/api/v1/platform/organizations")
class PlatformOrganizationController {

    record CreateOrganizationRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            @NotBlank @Size(max = 200) String adminDisplayName) {}

    record OrganizationResponse(
            UUID id,
            String name,
            OrganizationStatus status,
            @Nullable Instant createdAt) {

        static OrganizationResponse of(Organization o) {
            return new OrganizationResponse(o.getId(), o.getName(), o.getStatus(), o.getCreatedAt());
        }
    }

    record AdminResponse(
            UUID userId, String email, @Nullable String temporaryPassword) {}

    record CreatedOrganizationResponse(OrganizationResponse organization, AdminResponse admin) {}

    private final PlatformOrganizationService platform;

    PlatformOrganizationController(PlatformOrganizationService platform) {
        this.platform = platform;
    }

    @GetMapping
    @RequiresPermission(Permission.PLATFORM_ORG_MANAGE)
    PagedModel<OrganizationResponse> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return new PagedModel<>(platform.list(page, size).map(OrganizationResponse::of));
    }

    @PostMapping
    @RequiresPermission(Permission.PLATFORM_ORG_MANAGE)
    ResponseEntity<CreatedOrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        var created = platform.create(request.name(), request.adminEmail(), request.adminDisplayName());
        return ResponseEntity.created(URI.create("/api/v1/platform/organizations/"
                        + created.organization().getId()))
                .body(new CreatedOrganizationResponse(
                        OrganizationResponse.of(created.organization()),
                        new AdminResponse(created.adminUserId(), created.adminEmail(), created.temporaryPassword())));
    }
}
