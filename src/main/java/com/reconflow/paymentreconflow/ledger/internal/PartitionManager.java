package com.reconflow.paymentreconflow.ledger.internal;

import java.time.YearMonth;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes sure a monthly {@code transaction_record} partition exists before rows for that month
 * arrive (M5.3). A row whose month has no partition lands in {@code transaction_record_default},
 * and once the default holds rows for a month PostgreSQL refuses to create that month's partition,
 * so "create first, insert second" is the only safe order.
 *
 * <p>Each creation runs in its own short transaction (REQUIRES_NEW), never inside a chunk's: it
 * locks the parent table. A transaction-scoped advisory lock serializes creators on every node,
 * because two concurrent {@code CREATE TABLE IF NOT EXISTS ... PARTITION OF} for the same month can
 * still race and fail. The in-memory set means each month costs one round trip per JVM, not one per
 * chunk.
 */
@Component
public class PartitionManager {

    /** Any constant shared by every node; hashtext keeps it readable. */
    static final String LOCK_KEY = "transaction_record_partitions";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final Set<YearMonth> known = ConcurrentHashMap.newKeySet();

    PartitionManager(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensure(Collection<YearMonth> months) {
        for (YearMonth month : months) {
            if (known.contains(month)) {
                continue;
            }
            requiresNew.executeWithoutResult(status -> {
                // The cheap check first: an existing partition needs no lock on the parent.
                if (!exists(month)) {
                    jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, LOCK_KEY);
                    // V1's function is idempotent (CREATE TABLE IF NOT EXISTS).
                    jdbc.queryForObject("SELECT create_transaction_record_partition(?)", Object.class, month.atDay(1));
                }
            });
            known.add(month);
        }
    }

    public boolean exists(YearMonth month) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, partitionName(month)));
    }

    /** Rows in the default partition; anything above zero needs a manual repair. */
    public long defaultPartitionRows() {
        // Counted on the partition itself, which has no RLS policy of its own: a monitoring count
        // across every tenant that returns nothing but a number.
        Long rows = jdbc.queryForObject("SELECT count(*) FROM transaction_record_default", Long.class);
        return rows == null ? 0 : rows;
    }

    /**
     * The months that have a partition, from the catalog (M9.16: archival looks for old ones).
     */
    public java.util.List<YearMonth> existingMonths() {
        return jdbc
                .queryForList(
                        "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                                + " WHERE i.inhparent = 'transaction_record'::regclass"
                                + " AND c.relname ~ '^transaction_record_[0-9]{4}_[0-9]{2}$' ORDER BY 1",
                        String.class)
                .stream()
                .map(name -> YearMonth.of(
                        Integer.parseInt(name.substring(19, 23)), Integer.parseInt(name.substring(24, 26))))
                .toList();
    }

    /**
     * Detaches and drops a month's partition (M9.16, after its rows are archived and verified). Plain
     * DETACH, not CONCURRENTLY: CONCURRENTLY can't run while a default partition exists, nor inside
     * a transaction. It locks the parent briefly, so the job runs at night. Every foreign key into
     * the partition must already be gone (archival deletes the dependents first), or PostgreSQL
     * refuses.
     */
    public void drop(YearMonth month) {
        requiresNew.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, LOCK_KEY);
            String name = partitionName(month);
            jdbc.execute("ALTER TABLE transaction_record DETACH PARTITION " + name);
            jdbc.execute("DROP TABLE " + name);
        });
        known.remove(month);
    }

    static String partitionName(YearMonth month) {
        return "transaction_record_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    }
}
