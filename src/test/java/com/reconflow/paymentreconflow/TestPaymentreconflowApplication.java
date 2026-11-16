package com.reconflow.paymentreconflow;

import org.springframework.boot.SpringApplication;

/**
 * Runs the real application against Testcontainers instead of compose.yaml ("development-time
 * services"): {@code ./mvnw spring-boot:test-run}. Lives in src/test, so it never ships.
 */
public class TestPaymentreconflowApplication {

    public static void main(String[] args) {
        SpringApplication.from(PaymentreconflowApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
