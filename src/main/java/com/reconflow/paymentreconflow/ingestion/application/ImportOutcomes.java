package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How a batch ends (requirements.md §7.5), decided in one place, always inside the caller's
 * transaction with the batch row locked.
 */
@Component
public class ImportOutcomes {

    /** {@code failure_reason} when no row of the file was valid. */
    public static final String NO_VALID_ROWS = "no-valid-rows";

    private final Clock clock;

    ImportOutcomes(Clock clock) {
        this.clock = clock;
    }

    /**
     * Every row valid: COMPLETED. Some invalid: COMPLETED_WITH_ERRORS. None valid: FAILED, as
     * requirements.md §7.5 says (the phase plan said COMPLETED_WITH_ERRORS; the docs win).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finished(ImportBatch batch, int lastRow, Job job) {
        if (batch.getValidRows() == 0) {
            batch.fail(NO_VALID_ROWS, clock.instant());
        } else {
            batch.transitionTo(
                    batch.getErrorRows() == 0 ? ImportBatchStatus.COMPLETED : ImportBatchStatus.COMPLETED_WITH_ERRORS,
                    clock.instant());
        }
    }

    /** The job won't run again: the batch fails with the reason users will see. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failed(ImportBatch batch, String reason, Job job) {
        if (batch.getStatus().isTerminal()) {
            return;
        }
        batch.fail(reason, clock.instant());
    }
}
