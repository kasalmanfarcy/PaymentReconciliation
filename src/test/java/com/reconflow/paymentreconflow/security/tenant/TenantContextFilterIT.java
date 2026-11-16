package com.reconflow.paymentreconflow.security.tenant;

import static com.reconflow.paymentreconflow.support.TestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M3.4 against the real database: the one-join lookup and the resolution rules, through the full
 * filter chain. An unmapped path is the probe: once the tenant resolves, MVC answers 404 not-found.
 */
class TenantContextFilterIT extends AbstractIntegrationTest {

    private static final String PROBE = "/api/v1/tenant-probe";

    private ResultActions call(UUID userId, String orgHeader) throws Exception {
        var request = get(PROBE).header("Authorization", bearer(tokens().forUser(userId)));
        if (orgHeader != null) {
            request.header("X-Organization-Id", orgHeader);
        }
        return mockMvc.perform(request);
    }

    @Test
    void aMemberResolvesWithAndWithoutTheHeader() throws Exception {
        UUID org = data().newOrganization();
        var user = data().newMember(org, Role.VIEWER);

        call(user.id(), org.toString()).andExpect(jsonPath("$.code").value("not-found"));
        call(user.id(), null).andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void severalMembershipsNeedTheHeader() throws Exception {
        UUID a = data().newOrganization();
        UUID b = data().newOrganization();
        var user = data().newMember(a, Role.VIEWER);
        data().addMembership(user.id(), b, Role.INVESTIGATOR);

        call(user.id(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("organization-required"));
        call(user.id(), b.toString()).andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void anotherOrganizationAnUnknownOneAndASuspendedOneAreAll404() throws Exception {
        UUID mine = data().newOrganization();
        UUID theirs = data().newOrganization();
        var user = data().newMember(mine, Role.ORG_ADMIN);

        call(user.id(), theirs.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("organization-not-found"));
        call(user.id(), UUID.randomUUID().toString())
                .andExpect(jsonPath("$.code").value("organization-not-found"));
        data().suspendOrganization(mine);
        call(user.id(), mine.toString()).andExpect(jsonPath("$.code").value("organization-not-found"));
    }

    @Test
    void aMalformedHeaderIsAValidationFailure() throws Exception {
        var user = data().newMember(data().newOrganization(), Role.VIEWER);

        call(user.id(), "acme")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("X-Organization-Id"));
    }

    @Test
    void aUserDisabledMidSessionIsOutOnTheNextRequest() throws Exception {
        UUID org = data().newOrganization();
        var user = data().newMember(org, Role.ORG_ADMIN);
        call(user.id(), null).andExpect(jsonPath("$.code").value("not-found"));

        data().setStatus(user.id(), "DISABLED");

        // Same, still-valid access token: the status is checked on every request.
        call(user.id(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void removingAMembershipTakesEffectOnTheNextRequest() throws Exception {
        UUID org = data().newOrganization();
        var user = data().newMember(org, Role.ORG_ADMIN);
        call(user.id(), org.toString()).andExpect(jsonPath("$.code").value("not-found"));

        jdbc.update("delete from membership where user_id = ? and organization_id = ?", user.id(), org);

        call(user.id(), org.toString()).andExpect(jsonPath("$.code").value("organization-not-found"));
    }

    @Test
    void aPlatformAdminHasNoOrgAccess() throws Exception {
        var admin = data().newPlatformAdmin();

        call(admin.id(), null).andExpect(jsonPath("$.code").value("organization-not-found"));
        call(admin.id(), data().newOrganization().toString())
                .andExpect(jsonPath("$.code").value("organization-not-found"));
    }
}
