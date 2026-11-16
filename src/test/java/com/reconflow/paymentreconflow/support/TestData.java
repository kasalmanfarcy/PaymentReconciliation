package com.reconflow.paymentreconflow.support;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixtures written with plain SQL, so tests don't depend on the services under test to set up
 * their data, and every call makes fresh rows (new UUIDs, unique emails): tests never TRUNCATE and
 * never assume a table is empty.
 */
public final class TestData {

    public record TestUser(UUID id, String email) {}

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID newOrganization() {
        return newOrganization("Org " + Ids.newId());
    }

    public UUID newOrganization(String name) {
        UUID id = Ids.newId();
        jdbc.update("insert into organization (id, name) values (?, ?)", id, name);
        return id;
    }

    /**
     * An active user, linked to the identity provider with {@code external_subject = id} (M10.1), so
     * a token whose {@code sub} is the user's id authenticates as them.
     */
    public TestUser newUser() {
        UUID id = Ids.newId();
        String email = "user-" + id + "@example.test";
        jdbc.update(
                "insert into app_user (id, email, display_name, external_subject, must_change_password)"
                        + " values (?, ?, ?, ?, false)",
                id,
                email,
                "User " + id.toString().substring(0, 8),
                id.toString());
        return new TestUser(id, email);
    }

    /** A user that exists in ReconFlow but has never logged in through the IdP (no subject yet). */
    public TestUser newUnlinkedUser(String email) {
        UUID id = Ids.newId();
        jdbc.update(
                "insert into app_user (id, email, display_name, must_change_password) values (?, ?, ?, false)",
                id,
                email,
                "Unlinked " + id.toString().substring(0, 8));
        return new TestUser(id, email);
    }

    public TestUser newMember(UUID organizationId, Role role) {
        TestUser user = newUser();
        addMembership(user.id(), organizationId, role);
        return user;
    }

    public void addMembership(UUID userId, UUID organizationId, Role role) {
        jdbc.update(
                "insert into membership (user_id, organization_id, role) values (?, ?, ?)",
                userId,
                organizationId,
                role.name());
    }

    public TestUser newPlatformAdmin() {
        TestUser user = newUser();
        jdbc.update("update app_user set is_platform_admin = true where id = ?", user.id());
        return user;
    }

    public void setStatus(UUID userId, String status) {
        jdbc.update("update app_user set status = ? where id = ?", status, userId);
    }

    /**
     * An import batch row in the given status, written with SQL (no file, no job): enough for a
     * transaction record's foreign key or a read-API test.
     */
    public UUID newImportBatch(UUID organizationId, UUID uploadedBy, String sourceType, String status) {
        UUID id = Ids.newId();
        jdbc.update(
                "insert into import_batch (id, organization_id, source_type, status, file_name, file_size_bytes,"
                        + " content_hash, storage_key, uploaded_by) values (?, ?, ?, ?, ?, 1, ?, ?, ?)",
                id,
                organizationId,
                sourceType,
                status,
                "fixture-" + id + ".csv",
                String.format("%064x", new java.math.BigInteger(1, id.toString().getBytes()))
                        .substring(0, 64),
                organizationId + "/imports/" + id + "/original.csv",
                uploadedBy);
        return id;
    }

    public void suspendOrganization(UUID organizationId) {
        jdbc.update("update organization set status = 'SUSPENDED' where id = ?", organizationId);
    }
}
