package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The api/worker role switch: the worker bean exists only when the property is on. */
class JobWorkerRoleTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobWorkerProperties.class)
    @Import(JobWorker.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withBean(JobRunner.class, () -> mock(JobRunner.class));

    @Test
    void apiNodesHaveNoWorker() {
        runner.run(context -> assertThat(context).doesNotHaveBean(JobWorker.class));
        runner.withPropertyValues("reconflow.jobs.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JobWorker.class));
    }

    @Test
    void workerNodesStartTheWorker() {
        runner.withPropertyValues("reconflow.jobs.worker.enabled=true", "reconflow.jobs.worker.threads=2")
                .run(context -> {
                    assertThat(context).hasSingleBean(JobWorker.class);
                    assertThat(context.getBean(JobWorker.class).isRunning()).isTrue();
                    assertThat(context.getBean(JobWorkerProperties.class).threads())
                            .isEqualTo(2);
                });
    }
}
