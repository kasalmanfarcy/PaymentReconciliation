package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;

/**
 * M6.19, promise 1 as a property: for <em>any</em> set of records, every in-scope record ends up in
 * exactly one place (one match, or one case the run observed), nothing is lost and nothing extra
 * appears. jqwik generates the record sets and, on a failure, shrinks it to the smallest set that
 * still fails, printing it with its seed: turn that into a permanent example in WorkedExamplesIT.
 *
 * <p>The oracle computes only the <em>in-scope set</em>, straight from the participation table
 * (reconciliation-rules.md §2: P4 SUCCESS, P6 in the period, P5 the group has a record in the run's
 * currency; P1-P3 hold for every generated record). It deliberately doesn't re-implement the
 * classification: the partition property is the one that matters.
 *
 * <p>Seen failing on purpose: filtering candidates by the run's currency before grouping (ADR
 * 0009's rejected alternative) fails within a few tries and shrinks to two records, an AED ledger
 * and a USD gateway with one reference, the case WorkedExamplesIT pins. Removing the anchor-day
 * filter does <em>not</em> fail it: a group re-classified on a later day loads the same full group,
 * so its match hits ON CONFLICT DO NOTHING and its case is re-observed by key. The safety nets
 * hide the bug (the engine just does redundant work), which is why the filter is reasoned about,
 * not only tested.
 *
 * <p>jqwik properties aren't JUnit Jupiter tests, so Spring's extension doesn't apply. The context
 * comes from Spring's TestContextManager for an {@link Anchor} class with the same configuration
 * as every other IT: the test-context cache hands back the one already-running context and
 * PostgreSQL container instead of starting new ones.
 */
class EveryRecordInExactlyOnePlaceIT {

    static final LocalDate START = LocalDate.of(2026, 10, 1);
    static final LocalDate END = LocalDate.of(2026, 10, 31);
    static final String CURRENCY = "AED";

    /** Never run as a test: it only carries the shared IT configuration. */
    static final class Anchor extends AbstractIntegrationTest {}

    record Spec(String reference, String currency, String amount, LocalDate date, String status, SourceType side) {}

    private static ApplicationContext context;

    private static synchronized ApplicationContext context() {
        if (context == null) {
            context = new TestContextManager(Anchor.class).getTestContext().getApplicationContext();
        }
        return context;
    }

    @Property(tries = 100)
    void everyInScopeRecordLandsInExactlyOneBucket(@ForAll("recordSets") List<Spec> specs) {
        JdbcTemplate jdbc = context().getBean(JdbcTemplate.class);
        RunDriver driver = context().getBean(RunDriver.class);
        var f = new ReconFixture(jdbc); // a fresh tenant per try: no cleanup needed
        Map<UUID, Spec> inserted = new HashMap<>();
        for (Spec s : specs) {
            inserted.put(
                    f.record(s.side(), s.reference(), s.amount(), s.currency(), s.date(), s.status(), "PAYMENT", false),
                    s);
        }

        UUID run = driver.run(f, START, END, CURRENCY);

        assertThat(driver.status(run)).isEqualTo("COMPLETED");
        List<UUID> placed = new ArrayList<>(jdbc.queryForList(
                "SELECT unnest(ARRAY[ledger_record_id, gateway_record_id]) FROM reconciliation_match WHERE run_id = ?",
                UUID.class,
                run));
        placed.addAll(jdbc.queryForList(
                "SELECT r.transaction_record_id FROM exception_case_run cr"
                        + " JOIN exception_case_record r ON r.exception_id = cr.exception_id WHERE cr.run_id = ?",
                UUID.class,
                run));
        assertThat(placed).as("never counted twice").doesNotHaveDuplicates();
        assertThat(new HashSet<>(placed)).as("never lost, nothing extra").isEqualTo(inScope(inserted));
        // And the run's own summary accounts for every record of the period (NFR-01).
        context().getBean(RunOutcomes.class).assertInvariant(run);
    }

    /** The participation rules, in plain Java, over the generated specs. */
    static Set<UUID> inScope(Map<UUID, Spec> records) {
        Set<String> groupsInCurrency = new HashSet<>();
        records.values().forEach(s -> {
            if (participates(s) && s.currency().equals(CURRENCY)) {
                groupsInCurrency.add(s.reference());
            }
        });
        Set<UUID> inScope = new HashSet<>();
        records.forEach((id, s) -> {
            if (participates(s) && groupsInCurrency.contains(s.reference())) {
                inScope.add(id);
            }
        });
        return inScope;
    }

    private static boolean participates(Spec s) {
        return s.status().equals("SUCCESS")
                && !s.date().isBefore(START)
                && !s.date().isAfter(END);
    }

    @Provide
    Arbitrary<List<Spec>> recordSets() {
        // Few references: collisions (duplicates, mismatches, mixed currencies) on every try.
        Arbitrary<String> refs = Arbitraries.of("R1", "R2", "R3", "R4", "R5");
        Arbitrary<String> currency = Arbitraries.of("AED", "AED", "AED", "USD"); // mostly the run's
        // 100.0 and 100.00 in one pool: the compareTo rule (MN2) is exercised inside the property.
        Arbitrary<String> amount = Arbitraries.of("100.00", "100.0", "99.99");
        // -2..+33 days: some records fall outside the period and must be ignored.
        Arbitrary<LocalDate> date = Arbitraries.integers().between(-2, 33).map(START::plusDays);
        Arbitrary<String> status = Arbitraries.of("SUCCESS", "SUCCESS", "SUCCESS", "FAILED");
        Arbitrary<SourceType> side = Arbitraries.of(SourceType.class);
        return Combinators.combine(refs, currency, amount, date, status, side)
                .as(Spec::new)
                .list()
                .ofMinSize(0)
                .ofMaxSize(30);
    }
}
