package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Without the worker role (the test profile, like an API node) nothing is scheduled: no poller, no
 * sweeper, no maintenance job. A library that enables scheduling on its own (Spring Modulith's
 * Moments did) would make every API node run the daily jobs too.
 */
class SchedulingRoleIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void anApiNodeSchedulesNothing() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
        assertThat(context.getBeanNamesForType(JobWorker.class)).isEmpty();
    }
}
