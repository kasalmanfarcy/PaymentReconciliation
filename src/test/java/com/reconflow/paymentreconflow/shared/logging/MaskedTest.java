package com.reconflow.paymentreconflow.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskedTest {

    @Test
    void referencesKeepOnlyTheirLastFourCharacters() {
        assertThat(Masked.reference("PAY-0001234")).isEqualTo("****1234");
        assertThat(Masked.reference("ABCD")).isEqualTo("****");
        assertThat(Masked.reference("")).isEqualTo("****");
        assertThat(Masked.reference(null)).isNull();
    }

    @Test
    void emailsKeepTheFirstLetterAndTheDomain() {
        assertThat(Masked.email("jane.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(Masked.email("j@example.com")).isEqualTo("***@example.com");
        assertThat(Masked.email("@example.com")).isEqualTo("***@example.com");
        assertThat(Masked.email("not-an-email")).isEqualTo("***");
        assertThat(Masked.email(null)).isNull();
    }
}
