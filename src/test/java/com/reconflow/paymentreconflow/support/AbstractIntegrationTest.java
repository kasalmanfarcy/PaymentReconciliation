package com.reconflow.paymentreconflow.support;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

    // Helpers arrive with the domain, e.g. in M3: protected TestOrg newOrganization() { ... }
}
