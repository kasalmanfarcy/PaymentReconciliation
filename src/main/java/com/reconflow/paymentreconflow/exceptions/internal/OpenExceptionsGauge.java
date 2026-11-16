package com.reconflow.paymentreconflow.exceptions.internal;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.shared.context.TenantDirectory;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code reconflow.exceptions.open{type}} (M9.9): cases not yet RESOLVED, across all organizations.
 *
 * <p>Never a count per scrape: a scheduled refresh (every 60 s, worker nodes only) stores the totals
 * and the gauge reads them. Row-level security hides every case unless one organization is bound,
 * so the refresh is the system path of {@link TenantDirectory}: each organization bound in turn,
 * one indexed count each ({@code exception_case_list_idx} leads with organization and status). The
 * metric has no organization tag on purpose: per-tenant numbers belong in the reports, not in a
 * time series per tenant.
 */
@Component
public class OpenExceptionsGauge {

    private static final String COUNT = """
            SELECT type, count(*) FROM exception_case
             WHERE organization_id = ? AND status <> 'RESOLVED'
             GROUP BY type
            """;

    private final JdbcTemplate jdbc;
    private final TenantDirectory tenants;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final Map<ExceptionType, AtomicLong> open = new EnumMap<>(ExceptionType.class);

    OpenExceptionsGauge(
            JdbcTemplate jdbc,
            TenantDirectory tenants,
            TenantScope tenantScope,
            TransactionTemplate tx,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.tx = tx;
        for (ExceptionType type : ExceptionType.values()) {
            AtomicLong value = new AtomicLong();
            open.put(type, value);
            Gauge.builder("reconflow.exceptions.open", value, AtomicLong::get)
                    .description("Exception cases not yet resolved, all organizations (refreshed every 60 s)")
                    .tag("type", type.name())
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${reconflow.exceptions.open-gauge-interval:60s}", initialDelay = 10_000)
    public void refresh() {
        Map<ExceptionType, Long> totals = new EnumMap<>(ExceptionType.class);
        for (UUID org : tenants.organizationIds()) {
            tenantScope.run(
                    org,
                    () -> tx.executeWithoutResult(s -> jdbc.query(
                            COUNT,
                            rs -> {
                                totals.merge(ExceptionType.valueOf(rs.getString(1)), rs.getLong(2), Long::sum);
                            },
                            org)));
        }
        open.forEach((type, value) -> value.set(totals.getOrDefault(type, 0L)));
    }

    /** The current value, for tests (they call {@link #refresh()} directly). */
    public long value(ExceptionType type) {
        return open.get(type).get();
    }
}
