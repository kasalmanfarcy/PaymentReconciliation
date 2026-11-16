package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Rules K1/K2 (reconciliation-rules.md §6): the key identifies the discrepancy, not the run. */
class DiscrepancyKeyTest {

    @Test
    void theKeyIsTypeReferenceTransactionTypeAndTheSortedCurrencySet() {
        assertThat(Discrepancy.key(ExceptionType.AMOUNT_MISMATCH, "PAY-103", "PAYMENT", List.of("AED")))
                .isEqualTo("AMOUNT_MISMATCH:PAY-103:PAYMENT:AED");
        // Whichever currency's run sees it, the key is the same (K2).
        assertThat(Discrepancy.key(ExceptionType.CURRENCY_MISMATCH, "PAY-201", "PAYMENT", List.of("USD", "AED")))
                .isEqualTo(
                        Discrepancy.key(ExceptionType.CURRENCY_MISMATCH, "PAY-201", "PAYMENT", List.of("AED", "USD")))
                .isEqualTo("CURRENCY_MISMATCH:PAY-201:PAYMENT:AED/USD");
        // A set: a duplicate group with two AED records still says AED once.
        assertThat(Discrepancy.key(ExceptionType.DUPLICATE_IN_SOURCE, "PAY-104", "PAYMENT", List.of("AED", "AED")))
                .isEqualTo("DUPLICATE_IN_SOURCE:PAY-104:PAYMENT:AED");
    }

    @Test
    void aReferenceContainingAColonStaysUnambiguous() {
        String key = Discrepancy.key(ExceptionType.MISSING_IN_LEDGER, "INV:2026:7", "REFUND", List.of("AED"));
        assertThat(key).isEqualTo("MISSING_IN_LEDGER:INV:2026:7:REFUND:AED");
        // Read the type from the left and the last two fields from the right; the rest is the reference.
        assertThat(Discrepancy.referenceOf(key)).isEqualTo("INV:2026:7");
        assertThat(Discrepancy.referenceOf("AMOUNT_MISMATCH:PAY-103:PAYMENT:AED"))
                .isEqualTo("PAY-103");
        assertThat(Discrepancy.referenceOf("CURRENCY_MISMATCH:PAY-201:PAYMENT:AED/USD"))
                .isEqualTo("PAY-201");
        assertThatThrownBy(() -> Discrepancy.referenceOf("AMOUNT_MISMATCH:PAY-103"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyNeedsACurrency() {
        assertThatThrownBy(() -> Discrepancy.key(ExceptionType.AMOUNT_MISMATCH, "X", "PAYMENT", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
