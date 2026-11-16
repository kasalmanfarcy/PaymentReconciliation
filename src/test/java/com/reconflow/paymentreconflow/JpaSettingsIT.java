package com.reconflow.paymentreconflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Guards two settings someone might flip while debugging (M2.6, M2.7):
 * <ul>
 *   <li>{@code ddl-auto=validate}: Flyway owns the schema; Hibernate only checks the entities
 *       against it and fails startup on a mismatch.</li>
 *   <li>{@code open-in-view=false}: no lazy loading during JSON serialization, outside any
 *       transaction, holding a connection for the whole request.</li>
 * </ul>
 */
class JpaSettingsIT extends AbstractIntegrationTest {

    @Value("${spring.jpa.open-in-view}")
    boolean openInView;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    String ddlAuto;

    @Test
    void schemaIsOwnedByFlywayAndSessionsDoNotLeakIntoViews() {
        assertThat(openInView).isFalse();
        assertThat(ddlAuto).isEqualTo("validate");
    }
}
