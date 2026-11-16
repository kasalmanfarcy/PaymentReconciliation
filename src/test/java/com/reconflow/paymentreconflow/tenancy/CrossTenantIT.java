package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.util.UriTemplate;

/**
 * M3.9: for every registered endpoint, a user of org A can't read, list, update or act on org B.
 * Two dimensions per case:
 * <ol>
 *   <li>A's admin, acting in A, aims at B's resources: 404 (or a list without them), and nothing of
 *       B changes.</li>
 *   <li>A's admin sends {@code X-Organization-Id: B}: 404 organization-not-found before anything
 *       else runs.</li>
 * </ol>
 * The cases come from {@link CrossTenantCases} beans, so this grows with the API; CrossTenantCoverageIT
 * makes sure it does. Dynamic tests rather than a parameterized one, because the cases come from
 * the Spring context.
 */
class CrossTenantIT extends AbstractIntegrationTest {

    @Autowired
    List<CrossTenantCases> registries;

    @TestFactory
    Stream<DynamicTest> orgACannotTouchOrgB() {
        return registries.stream()
                .flatMap(r -> r.cases().stream())
                .flatMap(c -> Stream.of(
                        DynamicTest.dynamicTest(c.name() + " (acting in A)", () -> actingInA(c)),
                        DynamicTest.dynamicTest(c.name() + " (claiming B)", () -> claimingB(c))));
    }

    private void actingInA(CrossTenantCase c) throws Exception {
        var f = TenantFixture.twoOrgs(jdbc);
        var before = f.snapshotOrgB();

        MockHttpServletResponse res = call(f, c, f.orgA());

        switch (c.expect()) {
            case NOT_FOUND -> {
                assertThat(res.getStatus()).as(c.name()).isEqualTo(404);
                assertThat(res.getContentAsString()).contains("\"code\":\"not-found\"");
            }
            case LIST_EXCLUDES_B, AFFECTS_ONLY_A -> {
                assertThat(res.getStatus()).as(res.getContentAsString()).isBetween(200, 299);
                if (c.expect() == CrossTenantCase.Expectation.LIST_EXCLUDES_B) {
                    for (UUID id : f.orgBIds()) {
                        assertThat(res.getContentAsString()).as(c.name()).doesNotContain(id.toString());
                    }
                }
            }
        }
        assertThat(f.snapshotOrgB()).as("org B after " + c.name()).isEqualTo(before);
    }

    private void claimingB(CrossTenantCase c) throws Exception {
        var f = TenantFixture.twoOrgs(jdbc);
        var before = f.snapshotOrgB();

        MockHttpServletResponse res = call(f, c, f.orgB());

        assertThat(res.getStatus()).as(c.name()).isEqualTo(404);
        assertThat(res.getContentAsString()).contains("\"code\":\"organization-not-found\"");
        assertThat(f.snapshotOrgB()).isEqualTo(before);
    }

    private MockHttpServletResponse call(TenantFixture f, CrossTenantCase c, UUID orgHeader) throws Exception {
        Map<String, Object> vars = c.orgBTarget().apply(f);
        AbstractMockHttpServletRequestBuilder<?> req = request(
                        c.method(), new UriTemplate(c.pathTemplate()).expand(vars))
                .with(as(f.orgAAdmin().id()))
                .with(org(orgHeader));
        @Nullable Object body = c.body() == null ? null : c.body().apply(f);
        if (body instanceof MultipartBody part) {
            MockMultipartHttpServletRequestBuilder multipart = multipart(
                            c.method(), new UriTemplate(c.pathTemplate()).expand(vars))
                    .file(new MockMultipartFile("file", part.fileName(), part.contentType(), part.content()));
            part.params().forEach(multipart::param);
            req = multipart.with(as(f.orgAAdmin().id())).with(org(orgHeader));
        } else if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(req).andReturn().getResponse();
    }
}
