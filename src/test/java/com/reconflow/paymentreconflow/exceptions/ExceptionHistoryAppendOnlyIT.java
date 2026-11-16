package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M7.2: notes and status history are append-only in the database itself (V6), whatever the
 * application does. Inserting still works.
 */
class ExceptionHistoryAppendOnlyIT extends AbstractIntegrationTest {

    @Autowired
    JobRunner jobs;

    @Test
    void notesAndHistoryCanBeInserted() {
        var f = new ExceptionFixture(jdbc, jobs);
        UUID caseId = f.amountMismatch("PAY-103");

        UUID note = insertNote(f, caseId);

        assertThat(f.count("SELECT count(*) FROM exception_note WHERE id = ?", note))
                .isOne();
        // The recorder's creation row (NULL → OPEN) was an insert into the history table too.
        assertThat(f.historyRows(caseId)).isOne();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE exception_note SET body = 'rewritten' WHERE exception_id = ?",
                "DELETE FROM exception_note WHERE exception_id = ?",
                "UPDATE exception_status_history SET reason = 'rewritten' WHERE exception_id = ?",
                "DELETE FROM exception_status_history WHERE exception_id = ?"
            })
    void changesAreRejected(String sql) {
        var f = new ExceptionFixture(jdbc, jobs);
        UUID caseId = f.amountMismatch("PAY-103");
        insertNote(f, caseId);

        assertThatThrownBy(() -> jdbc.update(sql, caseId)).hasMessageContaining("is append-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception_note", "exception_status_history"})
    void truncateIsRejected(String table) {
        // Row triggers don't fire on TRUNCATE; V6 adds a statement trigger for it.
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + table))
                .hasMessageContaining(table + " is append-only: TRUNCATE");
    }

    private UUID insertNote(ExceptionFixture f, UUID caseId) {
        UUID id = Ids.newId();
        jdbc.update(
                "INSERT INTO exception_note (id, organization_id, exception_id, author_id, body) VALUES (?, ?, ?, ?, ?)",
                id,
                f.org,
                caseId,
                f.investigator(),
                "Gateway support confirms the fee.");
        return id;
    }
}
