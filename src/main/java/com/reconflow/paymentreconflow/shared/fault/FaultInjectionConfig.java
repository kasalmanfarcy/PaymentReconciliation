package com.reconflow.paymentreconflow.shared.fault;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production's injector does nothing; tests register a {@code @Primary} scripted one instead. */
@Configuration(proxyBeanMethods = false)
class FaultInjectionConfig {

    @Bean
    FaultInjector faultInjector() {
        return FaultInjector.NONE;
    }
}
