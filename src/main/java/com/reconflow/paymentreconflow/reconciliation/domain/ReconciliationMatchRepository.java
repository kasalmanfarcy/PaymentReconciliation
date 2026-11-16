package com.reconflow.paymentreconflow.reconciliation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Read-only, organization-scoped lookups of single matches. Matches are written by SQL only. */
public interface ReconciliationMatchRepository extends Repository<ReconciliationMatch, UUID> {

    Optional<ReconciliationMatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ReconciliationMatch> findByOrganizationIdAndRunIdAndLedgerRecordId(
            UUID organizationId, UUID runId, UUID ledgerRecordId);
}
