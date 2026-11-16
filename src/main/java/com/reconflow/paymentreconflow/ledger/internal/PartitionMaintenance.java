package com.reconflow.paymentreconflow.ledger.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code transaction_record} partitioned at least {@value #MONTHS_AHEAD} months ahead and
 * watches the default partition (M5.27, phase-M9 "M9.N1", plans/README.md ordering exception 3).
 *
 * <p>Why it can't wait: V1 created monthly partitions only up to 3 months after the day it ran.
 * After that, rows land in {@code transaction_record_default}, and PostgreSQL refuses to create a
 * month's partition while the default holds rows for that month. The longer that goes unnoticed,
 * the bigger the manual repair (move the rows out, create the partition, move them back).
 *
 * <p>Safe on several workers at once: each month is created through
 * {@link PartitionManager#ensure}, which is idempotent and serialized by an advisory lock, so a
 * second run is a few cheap existence checks.
 */
@Component
public class PartitionMaintenance {

    static final int MONTHS_AHEAD = 3;
    public static final String DEFAULT_ROWS_METRIC = "reconflow.partitions.default.rows";

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);

    public record Result(List<YearMonth> months, long defaultPartitionRows) {}

    private final PartitionManager partitions;
    private final Clock clock;
    private final AtomicLong defaultRows = new AtomicLong();

    PartitionMaintenance(PartitionManager partitions, Clock clock, ObjectProvider<MeterRegistry> registry) {
        this.partitions = partitions;
        this.clock = clock;
        // The alert (M9.10) fires on this gauge above zero; until then the ERROR log is the signal.
        registry.ifAvailable(r -> Gauge.builder(DEFAULT_ROWS_METRIC, defaultRows, AtomicLong::get)
                .description("Rows in transaction_record_default; must be 0")
                .register(r));
    }

    /** Today's month, in UTC: the same clock rule N7 uses to date records. */
    public Result maintain() {
        return maintain(YearMonth.now(clock.withZone(ZoneOffset.UTC)));
    }

    public Result maintain(YearMonth current) {
        List<YearMonth> months = new ArrayList<>();
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            months.add(current.plusMonths(i));
        }
        partitions.ensure(months);
        long rows = partitions.defaultPartitionRows();
        defaultRows.set(rows);
        if (rows > 0) {
            log.error(
                    "transaction_record_default holds {} rows: a monthly partition is missing for their dates."
                            + " Move them into a new partition before more arrive (database-design.md §5).",
                    rows);
        } else {
            log.info("Partitions ensured for {} to {}; default partition empty", months.getFirst(), months.getLast());
        }
        return new Result(months, rows);
    }
}
