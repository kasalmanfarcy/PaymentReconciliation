package com.reconflow.paymentreconflow.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper jsonMapper;

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
