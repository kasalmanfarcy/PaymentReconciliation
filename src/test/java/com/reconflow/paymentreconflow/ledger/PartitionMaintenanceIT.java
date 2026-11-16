package com.reconflow.paymentreconflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ledger.internal.PartitionMaintenance;
import com.reconflow.paymentreconflow.ledger.internal.PartitionManager;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M5.27: the window keeps moving, running twice is harmless, and the default partition is watched. */
class PartitionMaintenanceIT extends AbstractIntegrationTest {

    @Autowired
    PartitionMaintenance maintenance;

    @Autowired
    PartitionManager partitions;

    @Autowired
    MeterRegistry meters;

    @Test
    void fiveMonthsAfterV1ItExtendsTheWindowToThreeMonthsAhead() {
        // V1 created partitions up to 3 months after the test database was migrated (today); five
        // months on, the months +6..+8 don't exist yet.
        YearMonth later = YearMonth.now(ZoneOffset.UTC).plusMonths(5);
        assertThat(partitions.exists(later.plusMonths(3))).isFalse();

        PartitionMaintenance.Result first = maintenance.maintain(later);
        PartitionMaintenance.Result second = maintenance.maintain(later); // idempotent

        assertThat(first.months())
                .containsExactly(later, later.plusMonths(1), later.plusMonths(2), later.plusMonths(3));
        for (int i = 0; i <= 3; i++) {
            assertThat(partitions.exists(later.plusMonths(i)))
                    .as("partition " + later.plusMonths(i))
                    .isTrue();
        }
        assertThat(second).isEqualTo(first);
        assertThat(first.defaultPartitionRows()).isZero();
        assertThat(meters.get(PartitionMaintenance.DEFAULT_ROWS_METRIC).gauge().value())
                .isZero();
    }
}
