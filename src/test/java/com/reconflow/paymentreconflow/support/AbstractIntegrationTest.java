package com.reconflow.paymentreconflow.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for every integration test ({@code *IT}, run by Failsafe in {@code ./mvnw verify}).
 *
 * <p>All subclasses share one configuration, so Spring's test-context cache gives them one
 * application context and therefore one PostgreSQL container for the whole run. Anything that
 * changes the configuration per class ({@code @MockitoBean}, {@code @TestPropertySource}, other
 * profiles) starts a new context and a new container, so avoid it unless a test really needs it.
 *
 * <p>Isolation: tests never TRUNCATE (the audit_event trigger forbids it) and are not
 * {@code @Transactional} (that would hide commit-time behaviour). Instead each test creates its
 * own organization and asserts only on data inside it. Never assume a table is empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * The test's own window on the database, as the superuser (M10.7): fixtures and assertions see
     * every tenant's rows. It is a separate connection pool, so it never takes part in the
     * application's transactions; use {@link #appJdbc} for that.
     */
    protected JdbcTemplate jdbc;

    /** The application's JdbcTemplate: reconflow_app, row-level security applies. */
    @Autowired
    protected JdbcTemplate appJdbc;

    @Autowired
    void useSuperuserForFixtures(TestDatabase database) {
        this.jdbc = database.superuser();
    }

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Value("${reconflow.auth.jwt.issuer}")
    private String issuer;

    @Value("${reconflow.auth.jwt.audience}")
    private String audience;

    /** Access tokens minted with the app's own key (or deliberately not). */
    protected TestTokens tokens() {
        return new TestTokens(jwtEncoder, issuer, audience);
    }

    /** Authenticates a MockMvc request as the user, with a freshly minted access token. */
    protected RequestPostProcessor as(UUID userId) {
        String header = TestTokens.bearer(tokens().forUser(userId));
        return request -> {
            request.addHeader("Authorization", header);
            return request;
        };
    }

    /** Selects the organization for an org-scoped request. */
    protected static RequestPostProcessor org(UUID organizationId) {
        return request -> {
            request.addHeader("X-Organization-Id", organizationId.toString());
            return request;
        };
    }

    @Autowired
    private TenantScope tenantScope;

    /**
     * Runs application code (repositories, services) bound to an organization, the way a request
     * or a job would. Since M10.7 the application's connection is subject to row-level security,
     * so tenant-owned rows are invisible, and can't be written, with no tenant bound.
     */
    protected <T> T asTenant(UUID organizationId, Supplier<T> work) {
        return tenantScope.call(organizationId, work);
    }

    protected void inTenant(UUID organizationId, Runnable work) {
        tenantScope.call(organizationId, () -> {
            work.run();
            return null;
        });
    }

    /** Fresh organizations and users, written with plain SQL. */
    protected TestData data() {
        return new TestData(jdbc);
    }

    /**
     * POST /api/v1/auth/login from a random client IP, so the per-IP login rate limit (M3.3f)
     * never couples unrelated tests. Rate-limit tests set the IP themselves.
     */
    protected ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(randomClientIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    protected static RequestPostProcessor randomClientIp() {
        String ip = "10.%d.%d.%d"
                .formatted(
                        ThreadLocalRandom.current().nextInt(256),
                        ThreadLocalRandom.current().nextInt(256),
                        ThreadLocalRandom.current().nextInt(1, 255));
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
