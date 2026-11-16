package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.csv.CsvEncodingException;
import com.reconflow.paymentreconflow.ingestion.csv.CsvHeaderException;
import com.reconflow.paymentreconflow.ingestion.csv.CsvRows;
import com.reconflow.paymentreconflow.ingestion.csv.ParsedRow;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ingestion.domain.ImportRowErrorRepository;
import com.reconflow.paymentreconflow.ingestion.domain.RowErrorWriter;
import com.reconflow.paymentreconflow.ingestion.normalization.Normalizer;
import com.reconflow.paymentreconflow.ingestion.validation.RowValidation;
import com.reconflow.paymentreconflow.ingestion.validation.RowValidator;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.ledger.TransactionRecordWriter;
import com.reconflow.paymentreconflow.shared.fault.FaultInjector;
import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobContext;
import com.reconflow.paymentreconflow.shared.jobs.JobStoppedException;
import com.reconflow.paymentreconflow.shared.jobs.PermanentJobFailure;
import com.reconflow.paymentreconflow.shared.jobs.TransientJobFailure;
import com.reconflow.paymentreconflow.shared.jobs.TransientRetry;
import com.reconflow.paymentreconflow.shared.storage.FileStorage;
import com.reconflow.paymentreconflow.shared.storage.StorageUnavailableException;
import com.reconflow.paymentreconflow.shared.storage.StoredFileMissingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * The worker's import loop (M5.14): stream the file, validate and normalize each row, and commit
 * every {@code chunk-size} rows in one transaction together with the {@code last_committed_row}
 * checkpoint and the counters.
 *
 * <p>Exactly-once effects from at-least-once execution: this may run twice for one batch (a crash,
 * an expired lease). A resumed run skips every row up to the checkpoint, which committed atomically
 * with those rows, so it is always exactly right; {@code ON CONFLICT DO NOTHING} on the row key is
 * the guard behind it; every chunk transaction is fenced, so a worker that lost its lease can't
 * commit.
 *
 * <p>Deliberately not {@code @Transactional}: that would make the whole file one transaction (no
 * checkpoint, a crash loses everything, locks held for minutes). Each chunk gets its own
 * TransactionTemplate call, visible in the code. Partitions are created before a chunk's transaction
 * opens, never inside it.
 */
@Component
public class ImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);

    private final ImportBatchRepository batches;
    private final ImportRowErrorRepository errors;
    private final RowErrorWriter errorWriter;
    private final TransactionRecordWriter records;
    private final FileStorage storage;
    private final RowValidator validator;
    private final Normalizer normalizer;
    private final ImportProperties props;
    private final ImportProcessingHook hook;
    private final ImportOutcomes outcomes;
    private final TransactionTemplate tx;
    private final TransientRetry retry;
    private final FaultInjector faults;
    private final Clock clock;

    ImportProcessor(
            ImportBatchRepository batches,
            ImportRowErrorRepository errors,
            RowErrorWriter errorWriter,
            TransactionRecordWriter records,
            FileStorage storage,
            RowValidator validator,
            Normalizer normalizer,
            ImportProperties props,
            ImportProcessingHook hook,
            ImportOutcomes outcomes,
            TransactionTemplate tx,
            TransientRetry retry,
            FaultInjector faults,
            Clock clock) {
        this.batches = batches;
        this.errors = errors;
        this.errorWriter = errorWriter;
        this.records = records;
        this.storage = storage;
        this.validator = validator;
        this.normalizer = normalizer;
        this.props = props;
        this.hook = hook;
        this.outcomes = outcomes;
        this.tx = tx;
        this.retry = retry;
        this.faults = faults;
        this.clock = clock;
    }

    /** What the first transaction found: the batch to work on, and where it stopped last time. */
    private record Start(SourceType sourceType, String storageKey, int lastCommittedRow, long storedErrors) {}

    public void process(Job job, JobContext context) {
        UUID organizationId = job.organizationId();
        UUID batchId = job.uuid(ImportJobs.BATCH_ID);
        MDC.put("batchId", batchId.toString());
        try {
            // Each transaction is wrapped by the in-process retry (M9.3), never the other way round.
            Start start = retry.call("import.begin", () -> tx.execute(s -> begin(organizationId, batchId, context)));
            if (start == null) {
                return; // already finished: a repeated job is harmless, and the runner marks it DONE
            }
            Path file = createTempFile();
            try {
                download(start.storageKey(), file);
                int lastRow = readAndCommit(file, organizationId, batchId, start, context);
                faults.at(FaultPoints.IMPORT_AFTER_LAST_CHUNK);
                finish(organizationId, batchId, lastRow, job, context);
            } finally {
                deleteQuietly(file);
            }
        } finally {
            MDC.remove("batchId");
        }
    }

    /**
     * Locks the batch: RECEIVED becomes PROCESSING; PROCESSING (a resumed job) just continues;
     * a terminal batch means this job is a harmless repeat.
     */
    private @Nullable Start begin(UUID organizationId, UUID batchId, JobContext context) {
        ImportBatch batch = batches.lockByIdAndOrganizationId(batchId, organizationId)
                .orElseThrow(() -> new PermanentJobFailure("batch-not-found"));
        context.fence();
        if (batch.getStatus().isTerminal()) {
            log.info("Batch {} is already {}; nothing to do", batchId, batch.getStatus());
            return null;
        }
        if (batch.getStatus() == ImportBatchStatus.RECEIVED) {
            batch.transitionTo(ImportBatchStatus.PROCESSING, clock.instant());
            log.info("Processing batch {}", batchId);
        } else {
            // Not a transition: PROCESSING -> PROCESSING isn't in the state machine on purpose.
            log.info("Resuming batch {} after row {}", batchId, batch.getLastCommittedRow());
        }
        return new Start(
                batch.getSourceType(),
                batch.getStorageKey(),
                batch.getLastCommittedRow(),
                errors.countByOrganizationIdAndBatchId(organizationId, batchId));
    }

    private int readAndCommit(Path file, UUID organizationId, UUID batchId, Start start, JobContext context) {
        int lastRow = start.lastCommittedRow();
        int committedThrough = start.lastCommittedRow();
        int errorBudget = (int) Math.max(0, props.maxStoredErrors() - start.storedErrors());
        Chunk chunk = new Chunk(errorBudget);
        try (CsvRows rows = CsvRows.open(file, start.sourceType())) {
            for (ParsedRow row : rows) {
                int number = row.number();
                if (number > props.maxRows()) {
                    throw new PermanentJobFailure("too-many-rows");
                }
                if (number <= start.lastCommittedRow()) {
                    continue; // committed by an earlier run, together with the checkpoint
                }
                context.ensureLeaseHeld(); // the heartbeat may have found the lease gone: stop now
                RowValidation result = validator.validate(row, start.sourceType());
                if (result.isValid()) {
                    chunk.addValid(normalizer.normalize(result.valid(), batchId, start.sourceType()));
                } else {
                    chunk.addErrors(number, result.errors(), row.rawRow());
                }
                lastRow = number;
                hook.afterRow(batchId, number);
                if (chunk.rows() >= props.chunkSize()) {
                    errorBudget -= commit(organizationId, batchId, chunk, committedThrough, lastRow, context);
                    committedThrough = lastRow;
                    chunk = new Chunk(errorBudget);
                    if (context.stopRequested()) {
                        throw new JobStoppedException(); // hand back at a chunk boundary
                    }
                }
            }
            if (!chunk.isEmpty()) {
                commit(organizationId, batchId, chunk, committedThrough, lastRow, context);
            }
        } catch (CsvEncodingException e) {
            throw new PermanentJobFailure(e.rowNumber() == 0 ? "invalid-encoding in the header" : e.getMessage(), e);
        } catch (CsvHeaderException e) {
            throw new PermanentJobFailure("invalid-header", e); // checked at upload; only a changed file gets here
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lastRow;
    }

    /**
     * One chunk, one transaction: rows, row errors, checkpoint and counters, fenced. Retried whole on
     * a transient failure (M9.3); the checkpoint's compare-and-set makes a retry of a commit that did
     * land (a connection lost during COMMIT) a no-op instead of counting the chunk twice.
     */
    private int commit(
            UUID organizationId, UUID batchId, Chunk chunk, int previousRow, int lastRow, JobContext context) {
        records.ensurePartitions(chunk.months()); // own short transactions, before this one
        retry.run(
                "import.chunk",
                () -> tx.executeWithoutResult(s -> {
                    context.fence();
                    int updated = batches.recordChunk(
                            batchId,
                            organizationId,
                            previousRow,
                            lastRow,
                            chunk.valid().size(),
                            chunk.errorRows());
                    if (updated != 1) {
                        Optional<Integer> checkpoint = batches.findLastCommittedRow(batchId, organizationId);
                        if (checkpoint.isPresent() && checkpoint.get() == lastRow) {
                            log.info("Batch {} chunk up to row {} was already committed; skipping", batchId, lastRow);
                            return;
                        }
                        throw new IllegalStateException(
                                "Batch " + batchId + " is no longer PROCESSING at row " + previousRow);
                    }
                    records.insert(organizationId, chunk.valid());
                    errorWriter.insert(organizationId, batchId, chunk.errors());
                    faults.at(FaultPoints.IMPORT_BEFORE_CHUNK_COMMIT);
                }));
        faults.at(FaultPoints.IMPORT_AFTER_CHUNK_COMMIT);
        log.debug(
                "Committed batch {} up to row {}: {} valid, {} invalid",
                batchId,
                lastRow,
                chunk.valid().size(),
                chunk.errorRows());
        return chunk.errors().size();
    }

    /**
     * The finishing transaction: flag within-file duplicates (D1), check the counters, decide the
     * outcome, audit it and mark the job DONE, all or nothing. If the fence fails, everything rolls
     * back and the worker that now owns the job finishes it instead.
     */
    private void finish(UUID organizationId, UUID batchId, int lastRow, Job job, JobContext context) {
        retry.run(
                "import.finish",
                () -> tx.executeWithoutResult(s -> {
                    ImportBatch batch = batches.lockByIdAndOrganizationId(batchId, organizationId)
                            .orElseThrow(() -> new PermanentJobFailure("batch-not-found"));
                    if (batch.getStatus() != ImportBatchStatus.PROCESSING) {
                        return; // finished or failed by someone else meanwhile
                    }
                    context.fence();
                    records.markDuplicatesInBatch(organizationId, batchId);
                    long stored = records.countBatch(organizationId, batchId);
                    if (stored != batch.getValidRows()) {
                        // Should never happen: rows and counters commit together. Loud, not fatal.
                        log.error(
                                "Batch {} counts {} valid rows but has {} records",
                                batchId,
                                batch.getValidRows(),
                                stored);
                    }
                    hook.beforeFinishCommit(batchId);
                    outcomes.finished(batch, lastRow, job);
                    context.complete();
                }));
    }

    private void download(String key, Path file) {
        try {
            storage.download(key, file);
        } catch (StoredFileMissingException e) {
            throw new PermanentJobFailure("file-missing", e);
        } catch (StorageUnavailableException | SdkException e) {
            // A 503 or a dropped connection: a later attempt may well succeed.
            throw new TransientJobFailure("storage unavailable", e);
        }
    }

    private static Path createTempFile() {
        try {
            return Files.createTempFile("import-", ".csv");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}", file, e);
        }
    }
}
