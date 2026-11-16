package com.reconflow.paymentreconflow.reporting.export;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The export's two cheap checks before any CSV is written (M8.4). */
@Component
@Transactional(readOnly = true)
public class ExportQueries {

    public static final String RUN_EXISTS =
            "SELECT EXISTS (SELECT 1 FROM reconciliation_run WHERE organization_id = :orgId AND id = :runId)";

    public static final String COUNT_FOR_RUN =
            "SELECT count(*) FROM exception_case_run WHERE organization_id = :orgId AND run_id = :runId";

    private final JdbcClient jdbc;

    ExportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** By (id, organization_id): another organization's run is simply not there. */
    public boolean runExists(UUID orgId, UUID runId) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM reconciliation_run WHERE organization_id = :orgId AND id = :runId)")
                .param("orgId", orgId)
                .param("runId", runId)
                .query(Boolean.class)
                .single();
    }

    /** How many rows the export will have: an index-only count on exception_case_run's key. */
    public long countForRun(UUID orgId, UUID runId) {
        return jdbc.sql(COUNT_FOR_RUN)
                .param("orgId", orgId)
                .param("runId", runId)
                .query(Long.class)
                .single();
    }
}
