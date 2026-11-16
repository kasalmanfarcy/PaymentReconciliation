package com.reconflow.paymentreconflow.reporting.importquality;

import com.reconflow.paymentreconflow.reporting.common.ReportParams;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityQueries.BatchRow;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityQueries.DenominatorRow;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityQueries.ErrorRow;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.BatchQuality;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Bucket;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.CodeColumnCount;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.CodeCount;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Counts;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Granularity;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Mode;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Period;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds the import-quality report (M8.2): one batch, or every batch of a period by arrival bucket. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ImportQualityReports {

    /** ledger.SourceType's values; reporting may not depend on {@code ledger}, so checked by name. */
    static final Set<String> SOURCE_TYPES = Set.of("INTERNAL_LEDGER", "GATEWAY");

    private final ImportQualityQueries queries;
    private final TenantContext tenant;

    ImportQualityReports(ImportQualityQueries queries, TenantContext tenant) {
        this.queries = queries;
        this.tenant = tenant;
    }

    /** Exactly one of {@code batchId} or {@code from}/{@code to} (with optional granularity and source). */
    public ImportQualityReport report(
            @Nullable UUID batchId,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable String granularity,
            @Nullable String sourceType) {
        boolean overTime = from != null || to != null || granularity != null || sourceType != null;
        if (batchId != null && overTime) {
            throw new ValidationFailedException(
                    "batchId", "conflicting-parameters", "send either batchId or from and to, not both");
        }
        if (batchId != null) {
            return new ImportQualityReport(Mode.BATCH, forBatch(batchId), null, null);
        }
        if (!overTime) {
            throw new ValidationFailedException("batchId", "required", "send either batchId or from and to");
        }
        LocalDate start = ReportParams.required(from, "from");
        LocalDate end = ReportParams.required(to, "to");
        ReportParams.range(start, end);
        Granularity g = granularity(granularity);
        String source = sourceType(sourceType);
        Period period = new Period(start, end, g, source);
        return new ImportQualityReport(Mode.OVER_TIME, null, period, overTime(period));
    }

    private BatchQuality forBatch(UUID batchId) {
        UUID orgId = tenant.organizationId();
        BatchRow batch = queries.batch(orgId, batchId).orElseThrow(() -> new NotFoundException("Import", batchId));
        Counts stored = new Counts(0, 0);
        List<CodeCount> byCode = new ArrayList<>();
        List<CodeColumnCount> byCodeAndColumn = new ArrayList<>();
        for (ErrorRow r : queries.batchErrors(orgId, batchId)) {
            switch (r.level()) {
                case 3 -> stored = new Counts(r.errors(), r.errorRows());
                case 1 -> byCode.add(new CodeCount(r.errorCode(), r.errors(), r.errorRows()));
                default ->
                    byCodeAndColumn.add(new CodeColumnCount(r.errorCode(), r.column(), r.errors(), r.errorRows()));
            }
        }
        return new BatchQuality(
                batch.id(),
                batch.sourceType(),
                batch.status(),
                batch.fileName(),
                batch.createdAt(),
                batch.totalRows(),
                batch.validRows(),
                batch.errorRows(),
                stored,
                byCode,
                byCodeAndColumn);
    }

    private List<Bucket> overTime(Period p) {
        UUID orgId = tenant.organizationId();
        Map<LocalDate, List<ErrorRow>> errors =
                queries.errorsOverTime(orgId, p.from(), p.to(), p.granularity(), p.sourceType()).stream()
                        .collect(Collectors.groupingBy(ErrorRow::bucket));
        List<Bucket> buckets = new ArrayList<>();
        // Every bucket with at least one batch; a bucket whose batches had no errors gets zeros.
        for (DenominatorRow d : queries.denominators(orgId, p.from(), p.to(), p.granularity(), p.sourceType())) {
            Counts stored = new Counts(0, 0);
            List<CodeCount> byCode = new ArrayList<>();
            for (ErrorRow r : errors.getOrDefault(d.bucket(), List.of())) {
                if (r.level() == 1) {
                    stored = new Counts(r.errors(), r.errorRows());
                } else {
                    byCode.add(new CodeCount(r.errorCode(), r.errors(), r.errorRows()));
                }
            }
            buckets.add(new Bucket(d.bucket(), d.batches(), d.totalRows(), d.errorRows(), stored, byCode));
        }
        return buckets;
    }

    private static Granularity granularity(@Nullable String raw) {
        if (raw == null) {
            return Granularity.DAY;
        }
        try {
            return Granularity.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(
                    "granularity", "invalid-granularity", "granularity must be day, week or month");
        }
    }

    private static @Nullable String sourceType(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        if (!SOURCE_TYPES.contains(raw)) {
            throw new ValidationFailedException(
                    "sourceType", "invalid-source-type", "sourceType must be INTERNAL_LEDGER or GATEWAY");
        }
        return raw;
    }
}
