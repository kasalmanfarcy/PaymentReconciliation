package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.ingestion.csv.CsvEncodingException;
import com.reconflow.paymentreconflow.ingestion.csv.CsvHeaderException;
import com.reconflow.paymentreconflow.ingestion.csv.CsvRows;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.storage.FileStorage;
import com.reconflow.paymentreconflow.ingestion.storage.StorageKeys;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobQueue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /imports} (M5.7): everything that can be rejected without reading the whole file is
 * rejected now, so the user gets immediate feedback; everything else happens in the background.
 *
 * <p>The order is the design:
 * <ol>
 *   <li>cheap request checks (type, size, empty);</li>
 *   <li>one streaming pass for the SHA-256, one for the header and the first data row;</li>
 *   <li>store the file, <em>then</em> commit the batch, its job and its audit event in one
 *       transaction. No transaction spans storage and the database, so a crash between the two
 *       leaves either an orphan file (invisible, swept later) or nothing; the reverse order could
 *       leave a batch pointing at a file that doesn't exist.</li>
 * </ol>
 *
 * The file is never read into a {@code byte[]}: the container has buffered it to a temp file past
 * {@code file-size-threshold}, and every pass streams it.
 */
@Service
public class ImportReceiver {

    /** What browsers and tools send for a .csv (Windows with Excel installed: vnd.ms-excel). */
    static final Set<String> ACCEPTED_TYPES =
            Set.of("text/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream");

    private final ImportBatchRepository batches;
    private final FileStorage storage;
    private final JobQueue jobs;
    private final AuditService audit;
    private final TenantContext tenant;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final ImportProperties props;
    private final Clock clock;

    ImportReceiver(
            ImportBatchRepository batches,
            FileStorage storage,
            JobQueue jobs,
            AuditService audit,
            TenantContext tenant,
            CurrentUser currentUser,
            TransactionTemplate tx,
            ImportProperties props,
            Clock clock) {
        this.batches = batches;
        this.storage = storage;
        this.jobs = jobs;
        this.audit = audit;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public ImportView receive(@Nullable MultipartFile file, SourceType sourceType) {
        UUID organizationId = tenant.organizationId();
        checkRequest(file);
        String hash = sha256(file);
        inspectHeader(file, sourceType);

        UUID batchId = Ids.newId();
        // Server-generated IDs only: the original file name is user input and never touches a key.
        String key = StorageKeys.original(organizationId, batchId);
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), "text/csv");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ImportBatch batch = ImportBatch.received(
                batchId,
                organizationId,
                sourceType,
                displayName(file),
                file.getSize(),
                hash,
                key,
                currentUser.id(),
                clock.instant());
        tx.executeWithoutResult(s -> {
            batches.saveAndFlush(batch);
            jobs.enqueue(organizationId, ImportJobs.TYPE, ImportJobs.payload(batchId));
            audit.record(AuditEntry.of(AuditAction.IMPORT_RECEIVED, AuditEntityType.IMPORT_BATCH, batchId)
                    .with("fileName", batch.getFileName())
                    .with("sourceType", sourceType.name())
                    .with("sizeBytes", batch.getFileSizeBytes())
                    .with("contentHash", hash));
        });
        return ImportView.of(batch, 0);
    }

    /**
     * The Content-Type is client-controlled and only a first filter: browsers send any of four
     * types for a .csv. The real test is parsing the header.
     */
    private void checkRequest(@Nullable MultipartFile file) {
        if (file == null) {
            throw ImportRejectedException.emptyFile("No file was uploaded.");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".csv") || !acceptedType(file.getContentType())) {
            throw ImportRejectedException.unsupportedMediaType();
        }
        if (file.getSize() > props.maxFileSize().toBytes()) {
            throw ImportRejectedException.tooLarge(props.maxFileSize().toBytes());
        }
        if (file.isEmpty()) {
            throw ImportRejectedException.emptyFile("The file is empty.");
        }
    }

    private static boolean acceptedType(@Nullable String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true; // no type is no claim; the header check decides
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return ACCEPTED_TYPES.contains(type.getType() + "/" + type.getSubtype());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** SHA-256 of every byte, streamed: the file's identity for duplicate detection (ADR 0005). */
    private static String sha256(MultipartFile file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), md)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads only the header and the first data record, through the same parser the worker uses. */
    private static void inspectHeader(MultipartFile file, SourceType sourceType) {
        try (CsvRows rows = CsvRows.open(file.getInputStream(), sourceType)) {
            boolean hasData;
            try {
                hasData = rows.iterator().hasNext();
            } catch (CsvEncodingException e) {
                hasData = true; // a data row exists; its bytes are the worker's problem (batch FAILED)
            }
            if (!hasData) {
                throw ImportRejectedException.emptyFile("The file has a header but no data rows.");
            }
        } catch (CsvHeaderException e) {
            throw switch (e.kind()) {
                case NO_HEADER -> ImportRejectedException.emptyFile("The file has no header row.");
                case MISSING_COLUMNS -> ImportRejectedException.missingColumns(e.columns());
                case DUPLICATE_COLUMNS -> ImportRejectedException.duplicateColumns(e.columns());
            };
        } catch (CsvEncodingException e) {
            throw ImportRejectedException.invalidEncoding();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Display only; the browser may send a full client path ("C:\\Users\\me\\file.csv"). */
    private static String displayName(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = name.substring(slash + 1);
        return base.length() <= 255 ? base : base.substring(0, 255);
    }
}
