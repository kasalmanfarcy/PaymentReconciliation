package com.reconflow.paymentreconflow.ingestion.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class UploadLimitsCheckTest {

    @Test
    void agreeingLimitsPass() {
        assertThatCode(() -> UploadLimitsCheck.check(
                        DataSize.ofMegabytes(50), DataSize.ofMegabytes(51), DataSize.ofMegabytes(50)))
                .doesNotThrowAnyException();
    }

    @Test
    void aContainerLimitThatDisagreesWithTheCodeFailsStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> UploadLimitsCheck.check(
                        DataSize.ofMegabytes(100), DataSize.ofMegabytes(101), DataSize.ofMegabytes(50)))
                .withMessageContaining("disagree");
    }

    @Test
    void aRequestLimitBelowTheFileLimitFailsStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> UploadLimitsCheck.check(
                        DataSize.ofMegabytes(50), DataSize.ofMegabytes(10), DataSize.ofMegabytes(50)))
                .withMessageContaining("below");
    }
}
