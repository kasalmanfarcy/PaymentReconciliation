package com.reconflow.paymentreconflow.exceptions.internal.domain;

import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Write-only: notes are inserted, never changed. The detail view reads them with SQL. */
public interface ExceptionNoteRepository extends Repository<ExceptionNote, UUID> {

    ExceptionNote save(ExceptionNote note);
}
