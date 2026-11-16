package com.reconflow.paymentreconflow.exceptions.internal;

import com.reconflow.paymentreconflow.exceptions.CaseLinkArchive;
import com.reconflow.paymentreconflow.shared.storage.CsvArchive;
import com.reconflow.paymentreconflow.shared.storage.CsvArchive.ArchivedFile;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcCaseLinkArchive implements CaseLinkArchive {

    private static final String RANGE =
            " WHERE r.organization_id = ? AND r.transaction_date >= ? AND r.transaction_date < ?";

    private final JdbcTemplate jdbc;
    private final CsvArchive archive;

    JdbcCaseLinkArchive(JdbcTemplate jdbc, CsvArchive archive) {
        this.jdbc = jdbc;
        this.archive = archive;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long activeCasesLinkedTo(UUID organizationId, LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject(
                "SELECT count(DISTINCT c.id) FROM exception_case_record r"
                        + " JOIN exception_case c ON c.id = r.exception_id AND c.organization_id = r.organization_id"
                        + RANGE + " AND c.status <> 'RESOLVED'",
                Long.class,
                organizationId,
                from,
                to);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long countLinks(UUID organizationId, LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM exception_case_record r" + RANGE, Long.class, organizationId, from, to);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ArchivedFile exportLinks(UUID organizationId, LocalDate from, LocalDate to, String key) {
        return archive.export(
                key,
                "SELECT r.exception_id, r.transaction_record_id, r.transaction_date, r.organization_id"
                        + " FROM exception_case_record r" + RANGE + " ORDER BY r.exception_id, r.transaction_record_id",
                new Object[] {organizationId, from, to},
                null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteLinks(UUID organizationId, LocalDate from, LocalDate to) {
        return jdbc.update("DELETE FROM exception_case_record r" + RANGE, organizationId, from, to);
    }
}
