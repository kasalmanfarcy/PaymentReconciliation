package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.RlsProbe;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M3.5, layer 2 on its own: the V4 policies, exercised with plain SQL as a role that RLS applies
 * to. Each assertion would fail if the policy were dropped, because these queries deliberately
 * have no (or the wrong) organization filter: that is the mistake RLS exists to contain.
 */
class RowLevelSecurityIT extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    RlsProbe probe;
    UUID orgA;
    UUID orgB;
    UUID batchA;
    UUID batchB;

    @BeforeEach
    void twoTenantsWithOneBatchEach() {
        probe = new RlsProbe(dataSource, jdbc);
        orgA = data().newOrganization();
        orgB = data().newOrganization();
        UUID uploader = data().newUser().id();
        batchA = insertBatch(jdbc, orgA, uploader);
        batchB = insertBatch(jdbc, orgB, uploader);
    }

    static UUID insertBatch(JdbcTemplate jdbc, UUID org, UUID uploader) {
        UUID id = Ids.newId();
        jdbc.update(
                "insert into import_batch (id, organization_id, source_type, file_name, file_size_bytes,"
                        + " content_hash, storage_key, uploaded_by) values (?, ?, 'GATEWAY', 'f.csv', 1, ?, ?, ?)",
                id,
                org,
                (id.toString().replace("-", "") + "0".repeat(32)).substring(0, 64),
                org + "/imports/" + id,
                uploader);
        return id;
    }

    @Test
    void aTenantSeesOnlyItsOwnRowsEvenWithoutAWhereClause() throws Exception {
        long visible = probe.asTenant(orgA, db -> db.queryForObject("select count(*) from import_batch", Long.class));
        long ofB = probe.asTenant(
                orgA,
                db -> db.queryForObject(
                        "select count(*) from import_batch where organization_id = ?", Long.class, orgB));
        long byIdOfB = probe.asTenant(
                orgA, db -> db.queryForObject("select count(*) from import_batch where id = ?", Long.class, batchB));

        assertThat(visible).isEqualTo(1); // A is a fresh organization with exactly one batch
        assertThat(ofB).isZero();
        assertThat(byIdOfB).isZero();
        // Sanity: the rows are there, the policy hides them (the superuser sees both).
        assertThat(jdbc.queryForObject(
                        "select count(*) from import_batch where id in (?, ?)", Long.class, batchA, batchB))
                .isEqualTo(2);
    }

    @Test
    void writingARowForAnotherTenantIsRejected() {
        UUID uploader = data().newUser().id();

        assertThatThrownBy(() -> probe.asTenant(orgA, db -> insertBatch(db, orgB, uploader)))
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void withNoTenantBoundNothingIsVisible() throws Exception {
        long visible = probe.asTenant(
                null,
                db -> db.queryForObject(
                        "select count(*) from import_batch where id in (?, ?)", Long.class, batchA, batchB));

        assertThat(visible).isZero(); // fails closed
    }

    @Test
    void theSettingDiesWithItsTransactionOnAPooledConnection() throws Exception {
        probe.onOneConnection(db -> {
            var connection = db.getDataSource().getConnection();
            connection.setAutoCommit(false);
            db.execute("SET LOCAL ROLE rls_probe");
            db.queryForObject("SELECT set_config('app.org_id', ?, true)", String.class, orgA.toString());
            assertThat(db.queryForObject("select count(*) from import_batch where id = ?", Long.class, batchA))
                    .isEqualTo(1);
            connection.commit();

            // Next transaction, same connection: SET LOCAL is gone. current_setting now returns ''
            // rather than NULL, which NULLIF turns back into "no tenant" instead of a cast error.
            db.execute("SET LOCAL ROLE rls_probe");
            assertThat(db.queryForObject("select current_setting('app.org_id', true)", String.class))
                    .isEmpty();
            assertThat(db.queryForObject("select app_current_org()", UUID.class))
                    .isNull();
            assertThat(db.queryForObject("select count(*) from import_batch where id = ?", Long.class, batchA))
                    .isZero();
            connection.rollback();
            connection.setAutoCommit(true);
            return null;
        });
    }

    @Test
    void auditRowsAreReadableOnlyByTheirOrganizationAndWritableOnlyForTheBoundOne() throws Exception {
        UUID entity = Ids.newId();
        jdbc.update(
                "insert into audit_event (id, organization_id, action, entity_type, entity_id) values (?, ?, 'LOGOUT', 'USER', ?)",
                Ids.newId(),
                orgB,
                entity);
        jdbc.update(
                "insert into audit_event (id, organization_id, action, entity_type, entity_id) values (?, null, 'LOGOUT', 'USER', ?)",
                Ids.newId(),
                entity);

        assertThat(probe.<Long>asTenant(
                        orgA,
                        db -> db.queryForObject(
                                "select count(*) from audit_event where entity_id = ?", Long.class, entity)))
                .as("neither B's row nor the platform (NULL-org) row")
                .isZero();
        assertThat(probe.<Long>asTenant(
                        orgB,
                        db -> db.queryForObject(
                                "select count(*) from audit_event where entity_id = ?", Long.class, entity)))
                .isEqualTo(1);
        assertThatThrownBy(() -> probe.asTenant(
                        orgA,
                        db -> db.update(
                                "insert into audit_event (id, organization_id, action, entity_type) values (?, ?, 'LOGOUT', 'USER')",
                                Ids.newId(),
                                orgB)))
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
        // A platform event (NULL org) with no tenant bound is allowed: logins work.
        assertThat(probe.<Integer>asTenant(
                        null,
                        db -> db.update(
                                "insert into audit_event (id, organization_id, action, entity_type) values (?, null, 'LOGIN_FAILED', 'USER')",
                                Ids.newId())))
                .isEqualTo(1);
    }
}
