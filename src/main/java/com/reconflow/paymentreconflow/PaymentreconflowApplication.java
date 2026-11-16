package com.reconflow.paymentreconflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// Registers every @ConfigurationProperties record (e.g. shared.config.StorageProperties).
@ConfigurationPropertiesScan
public class PaymentreconflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentreconflowApplication.class, args);
    }
}
