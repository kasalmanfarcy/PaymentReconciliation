package com.reconflow.paymentreconflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M6.22: 1000.0 and 1000.00 are the same amount. Pins the trap in plain Java, and proves the
 * ArchUnit rule that bans {@code BigDecimal.equals} really fires: a class that compares money with
 * {@code equals} (below, test sources only) fails it.
 */
class MoneyEqualityTest {

    static final BigDecimal ONE_DECIMAL = new BigDecimal("1000.0");
    static final BigDecimal TWO_DECIMALS = new BigDecimal("1000.00");

    /** What a careless matcher would write. Exists only to be caught by the rule. */
    static final class CarelessMatcher {
        static boolean sameAmount(BigDecimal ledger, BigDecimal gateway) {
            return ledger.equals(gateway);
        }
    }

    /** What the code writes instead. */
    static boolean sameAmount(BigDecimal ledger, BigDecimal gateway) {
        return ledger.compareTo(gateway) == 0;
    }

    @Test
    void equalsComparesTheScaleAndCompareToTheValue() {
        assertThat(CarelessMatcher.sameAmount(ONE_DECIMAL, TWO_DECIMALS)).isFalse(); // the bug
        assertThat(sameAmount(ONE_DECIMAL, TWO_DECIMALS)).isTrue();
        assertThat(ONE_DECIMAL).isEqualByComparingTo(TWO_DECIMALS);
    }

    @Test
    void hashKeysHideTheSameTrap() {
        Set<BigDecimal> amounts = new HashSet<>(Set.of(ONE_DECIMAL, TWO_DECIMALS));
        assertThat(amounts).hasSize(2); // two "different" amounts
        Set<BigDecimal> normalized = new HashSet<>();
        amounts.forEach(a -> normalized.add(a.stripTrailingZeros()));
        assertThat(normalized).hasSize(1);
    }

    @Test
    void theArchitectureRuleCatchesEquals() {
        var careless = new ClassFileImporter().importClasses(CarelessMatcher.class);
        assertThatThrownBy(() -> ArchitectureRulesTest.NO_BIG_DECIMAL_EQUALS.check(careless))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BigDecimal.equals")
                .hasMessageContaining("compareTo");
        // The careful version passes.
        ArchitectureRulesTest.NO_BIG_DECIMAL_EQUALS.check(
                new ClassFileImporter().importClasses(MoneyEqualityTest.class));
    }
}
