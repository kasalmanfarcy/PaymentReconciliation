package com.reconflow.paymentreconflow.exceptions;

import com.reconflow.paymentreconflow.shared.storage.CsvArchive.ArchivedFile;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The exceptions module's part of M9.16's archival: the links from cases to the transaction records
 * of a month being archived ({@code exception_case_record} has a foreign key into the partition, so
 * the links go first). Every method runs in the caller's transaction with the organization bound.
 */
public interface CaseLinkArchive {

    /** Cases not RESOLVED that link a record dated in {@code [from, to)}: archival waits for them. */
    long activeCasesLinkedTo(UUID organizationId, LocalDate from, LocalDate to);

    long countLinks(UUID organizationId, LocalDate from, LocalDate to);

    ArchivedFile exportLinks(UUID organizationId, LocalDate from, LocalDate to, String key);

    /** The case itself stays (with its history); only its link to the archived record goes. */
    int deleteLinks(UUID organizationId, LocalDate from, LocalDate to);
}
