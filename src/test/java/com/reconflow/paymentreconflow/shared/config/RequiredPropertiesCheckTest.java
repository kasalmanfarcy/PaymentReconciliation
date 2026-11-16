package com.reconflow.paymentreconflow.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class RequiredPropertiesCheckTest {

    private final RequiredPropertiesCheck check = new RequiredPropertiesCheck();

    @Test
    void passesWhenNothingIsRequired() {
        assertThatCode(() -> check.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void passesWhenEveryRequiredPropertyHasAValue() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(RequiredPropertiesCheck.PROPERTY, "DB_URL, DB_USER")
                .withProperty("DB_URL", "jdbc:postgresql://db/reconflow")
                .withProperty("DB_USER", "app");

        assertThatCode(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsNamingEveryMissingOrBlankProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(RequiredPropertiesCheck.PROPERTY, "DB_URL, DB_USER, DB_PASSWORD")
                .withProperty("DB_USER", "   ")
                .withProperty("DB_PASSWORD", "secret");

        assertThatIllegalStateException()
                .isThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .withMessageContaining("[DB_URL, DB_USER]")
                .withMessageNotContaining("DB_PASSWORD]");
    }
}
