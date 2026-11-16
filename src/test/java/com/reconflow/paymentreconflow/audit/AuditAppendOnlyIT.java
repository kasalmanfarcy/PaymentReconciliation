package com.reconflow.paymentreconflow.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M4.2: the database itself refuses to change audit history, from any client, whatever the
 * application code does. The V1 triggers are the layer that stops bugs; revoking the privileges
 * from the application role (M10.7) is the layer that stops a compromised application, because a
 * table owner can disable triggers.
 */
class AuditAppendOnlyIT extends AbstractIntegrationTest {

    private UUID insertOneEvent() {
        UUID id = Ids.newId();
        jdbc.update(
                "insert into audit_event (id, action, entity_type, payload) values (?, 'LOGOUT', 'USER', '{}')", id);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE audit_event SET action = 'X' WHERE id = ?", "DELETE FROM audit_event WHERE id = ?"})
    void databaseRejectsChanges(String sql) {
        UUID id = insertOneEvent();

        assertThatThrownBy(() -> jdbc.update(sql, id)).hasMessageContaining("audit_event is append-only");
    }

    @Test
    void databaseRejectsTruncate() {
        // Row triggers don't fire on TRUNCATE; V1 adds a statement trigger for it.
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_event"))
                .hasMessageContaining("audit_event is append-only: TRUNCATE");
    }
}
