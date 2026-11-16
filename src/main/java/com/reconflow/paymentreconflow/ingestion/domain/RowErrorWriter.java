package com.reconflow.paymentreconflow.ingestion.domain;

import com.reconflow.paymentreconflow.shared.id.Ids;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts row errors with JDBC batching, in the caller's transaction (the chunk's), so errors,
 * records and the checkpoint commit together.
 *
 * <p>{@code ON CONFLICT DO NOTHING} against {@code UNIQUE NULLS NOT DISTINCT (batch_id, row_number,
 * column_name, error_code)}: a resumed import that re-reads a row already reported can't report it
 * twice. {@code NULLS NOT DISTINCT} (PostgreSQL 15+) is what makes two whole-row errors (NULL
 * column) collide; a plain UNIQUE would treat NULLs as all different.
 */
@Component
public class RowErrorWriter {

    private static final String INSERT = """
            INSERT INTO import_row_error
              (id, organization_id, batch_id, row_number, column_name, error_code, message, raw_row)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (batch_id, row_number, column_name, error_code) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    RowErrorWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID organizationId, UUID batchId, List<NewRowError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, errors, 500, (ps, e) -> {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, organizationId);
            ps.setObject(3, batchId);
            ps.setInt(4, e.rowNumber());
            ps.setString(5, e.column());
            ps.setString(6, e.code().name());
            ps.setString(7, e.message());
            ps.setString(8, e.rawRow());
        });
    }

    /** M5.20: a retry starts from a clean slate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteBatch(UUID organizationId, UUID batchId) {
        return jdbc.update(
                "DELETE FROM import_row_error WHERE organization_id = ? AND batch_id = ?", organizationId, batchId);
    }
}
