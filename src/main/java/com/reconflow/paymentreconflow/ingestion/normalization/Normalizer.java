package com.reconflow.paymentreconflow.ingestion.normalization;

import com.reconflow.paymentreconflow.ingestion.validation.ValidatedRow;
import com.reconflow.paymentreconflow.ledger.NewTransactionRecord;
import com.reconflow.paymentreconflow.ledger.ReferenceNormalizer;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.id.Ids;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Rules N1–N11 (reconciliation-rules.md §1, the single source of truth; if the two disagree, the
 * document wins and this is the bug). Produces the canonical values matching compares while keeping
 * the raw ones, so a normalization bug can later be fixed and replayed from {@code raw_values}
 * instead of being baked in forever. Both sources go through exactly this code.
 *
 * <p>Runs only on rows that passed validation, so nothing here can fail on user input.
 */
@Component
public class Normalizer {

    public NewTransactionRecord normalize(ValidatedRow row, UUID batchId, SourceType sourceType) {
        Instant at = row.transactionAt() == null ? null : row.transactionAt().toInstant(); // N7: a UTC instant
        LocalDate date = at != null
                // N7: the UTC date of the instant, so both sources use one clock and one partition:
                // 02:00 in Dubai (+04:00) on 1 October is 30 September.
                ? at.atZone(ZoneOffset.UTC).toLocalDate()
                // N8: a date-only ledger row is taken as it is; there is no time to convert.
                : Objects.requireNonNull(row.transactionDate(), "validated rows have a date");
        return new NewTransactionRecord(
                Ids.newId(),
                batchId,
                sourceType,
                row.number(),
                row.rawReference(), // N11: exactly as in the file, untrimmed
                ReferenceNormalizer.normalize(row.rawReference()), // N1-N4
                // N6: scale 4 as stored. UNNECESSARY: throws rather than rounds, and validation has
                // already rejected anything with more than 4 decimals, so it never throws here.
                row.amount().setScale(4, RoundingMode.UNNECESSARY),
                row.currency().strip().toUpperCase(Locale.ROOT), // N5
                TransactionTypeMapping.map(row.transactionType()).orElseThrow(), // N10
                StatusMapping.map(row.status()).orElseThrow(), // N9
                date,
                at,
                row.rawValues()); // N11
    }
}
