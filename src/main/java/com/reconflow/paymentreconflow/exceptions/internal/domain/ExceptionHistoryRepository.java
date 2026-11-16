package com.reconflow.paymentreconflow.exceptions.internal.domain;

import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Write-only: history rows are inserted, never changed. The detail view reads them with SQL. */
public interface ExceptionHistoryRepository extends Repository<ExceptionStatusHistory, UUID> {

    ExceptionStatusHistory save(ExceptionStatusHistory row);
}
