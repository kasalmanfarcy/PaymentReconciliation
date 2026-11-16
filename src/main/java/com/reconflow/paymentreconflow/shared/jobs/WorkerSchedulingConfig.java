package com.reconflow.paymentreconflow.shared.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} jobs (sweepers, maintenance) run only on worker nodes: API nodes never start
 * the scheduler, so two API instances can't run the same daily job twice each.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("reconflow.jobs.worker.enabled")
class WorkerSchedulingConfig {}
