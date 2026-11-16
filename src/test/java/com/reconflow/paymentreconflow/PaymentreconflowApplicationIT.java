package com.reconflow.paymentreconflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** The context starts, Flyway applies V1–V3, and Hibernate's schema validation passes. */
class PaymentreconflowApplicationIT extends AbstractIntegrationTest {

    @Test
    void contextLoadsAndMigrationsApply() {
        Integer applied =
                jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(3);
    }
}
