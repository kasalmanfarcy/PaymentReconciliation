package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.HistoryView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.NoteView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.RecordView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.Records;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.Resolution;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.RunRef;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ResolutionCode;
import com.reconflow.paymentreconflow.security.directory.UserDirectory;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of the caller's cases. Read-only transactions: with RLS the transaction is also what binds
 * {@code app.org_id} (M3.5), so even reads run in one. Every query filters by organization; another
 * tenant's case is a 404 identical to an unknown id (ADR 0008).
 *
 * <p>The linked records are read from {@code transaction_record} (owned by {@code ledger}) with SQL,
 * read-only, like the matching engine and transaction search do (architecture.md §4): one join that
 * prunes to a partition per record, rather than a Java API this module may not depend on.
 */
@Service
@Transactional(readOnly = true)
public class ExceptionQueries {

    /** Records per case in the detail view (api-design.md §8.14); a duplicate case can link many. */
    public static final int MAX_RECORDS = 500;

    private static final String CASE = """
            SELECT id, type, status, reason_code, discrepancy_key, currency, amount, amount_difference,
                   assigned_to, resolution_code, resolution_note, resolved_by, resolved_at, run_id, version,
                   created_at, updated_at
              FROM exception_case
             WHERE id = :id AND organization_id = :org
            """;

    // (id, transaction_date) is the partitioned table's full key: each lookup prunes to one partition.
    private static final String RECORDS = """
            SELECT t.id, t.source_type, t.raw_reference, t.normalized_reference, t.amount, t.currency,
                   t.transaction_type, t.status, t.transaction_date, t.transaction_at, t.batch_id,
                   t.source_row_number, t.is_duplicate_in_source, t.raw_values::text AS raw_values
              FROM exception_case_record l
              JOIN transaction_record t
                ON t.id = l.transaction_record_id AND t.transaction_date = l.transaction_date
               AND t.organization_id = l.organization_id
             WHERE l.exception_id = :id AND l.organization_id = :org
             ORDER BY t.source_type, t.transaction_date, t.source_row_number, t.id
             LIMIT :limit
            """;

    private static final String RUNS = """
            SELECT run_id, created, created_at FROM exception_case_run
             WHERE exception_id = :id AND organization_id = :org
             ORDER BY created_at, run_id
            """;

    private static final String NOTES = """
            SELECT id, author_id, body, created_at FROM exception_note
             WHERE exception_id = :id AND organization_id = :org
             ORDER BY created_at, id
            """;

    // UUIDv7 ids break created_at ties in insertion order (two transitions in one millisecond).
    private static final String HISTORY = """
            SELECT id, from_status, to_status, reason, resolution_code, resolution_note, actor_id, created_at
              FROM exception_status_history
             WHERE exception_id = :id AND organization_id = :org
             ORDER BY created_at, id
            """;

    private final JdbcClient jdbc;
    private final TenantContext tenant;
    private final UserDirectory users;
    private final JsonMapper json;

    ExceptionQueries(JdbcClient jdbc, TenantContext tenant, UserDirectory users, JsonMapper json) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.users = users;
        this.json = json;
    }

    public ExceptionDetailView detail(UUID id) {
        UUID org = tenant.organizationId();
        Map<String, Object> c = jdbc.sql(CASE).param("id", id).param("org", org).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Exception", id));

        List<RecordView> ledger = new ArrayList<>();
        List<RecordView> gateway = new ArrayList<>();
        jdbc.sql(RECORDS)
                .param("id", id)
                .param("org", org)
                .param("limit", MAX_RECORDS)
                .query(rs -> {
                    ("GATEWAY".equals(rs.getString("source_type")) ? gateway : ledger).add(record(rs));
                });
        List<RunRef> runs = jdbc.sql(RUNS)
                .param("id", id)
                .param("org", org)
                .query((rs, n) -> new RunRef(
                        rs.getObject("run_id", UUID.class), rs.getBoolean("created"), instant(rs, "created_at")))
                .list();
        List<NoteRow> notes = jdbc.sql(NOTES)
                .param("id", id)
                .param("org", org)
                .query((rs, n) -> new NoteRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("body"),
                        instant(rs, "created_at")))
                .list();
        List<HistoryRow> history = jdbc.sql(HISTORY)
                .param("id", id)
                .param("org", org)
                .query((rs, n) -> new HistoryRow(
                        rs.getObject("id", UUID.class),
                        enumOrNull(ExceptionStatus.class, rs.getString("from_status")),
                        ExceptionStatus.valueOf(rs.getString("to_status")),
                        rs.getString("reason"),
                        enumOrNull(ResolutionCode.class, rs.getString("resolution_code")),
                        rs.getString("resolution_note"),
                        rs.getObject("actor_id", UUID.class),
                        instant(rs, "created_at")))
                .list();

        // One batched name lookup for every user the page shows, not one per note.
        Set<UUID> userIds = new HashSet<>();
        addIfPresent(userIds, (UUID) c.get("assigned_to"));
        addIfPresent(userIds, (UUID) c.get("resolved_by"));
        notes.forEach(n -> userIds.add(n.authorId()));
        history.forEach(h -> addIfPresent(userIds, h.actorId()));
        Map<UUID, String> names = users.displayNames(userIds);

        ResolutionCode resolutionCode = enumOrNull(ResolutionCode.class, (String) c.get("resolution_code"));
        Resolution resolution = resolutionCode == null
                ? null
                : new Resolution(
                        resolutionCode,
                        (String) c.get("resolution_note"),
                        UserRef.of((UUID) c.get("resolved_by"), names),
                        toInstant(c.get("resolved_at")));
        String key = (String) c.get("discrepancy_key");
        return new ExceptionDetailView(
                id,
                ExceptionType.valueOf((String) c.get("type")),
                ExceptionStatus.valueOf((String) c.get("status")),
                (String) c.get("reason_code"),
                key,
                Discrepancy.referenceOf(key),
                (String) c.get("currency"),
                (java.math.BigDecimal) c.get("amount"),
                (java.math.BigDecimal) c.get("amount_difference"),
                UserRef.of((UUID) c.get("assigned_to"), names),
                resolution,
                (UUID) c.get("run_id"),
                ((Number) c.get("version")).longValue(),
                toInstant(c.get("created_at")),
                toInstant(c.get("updated_at")),
                new Records(ledger, gateway),
                runs,
                notes.stream()
                        .map(n -> new NoteView(n.id(), UserRef.of(n.authorId(), names), n.body(), n.createdAt()))
                        .toList(),
                history.stream()
                        .map(h -> new HistoryView(
                                h.id(),
                                h.from(),
                                h.to(),
                                h.reason(),
                                h.resolutionCode(),
                                h.resolutionNote(),
                                UserRef.of(h.actorId(), names),
                                h.at()))
                        .toList());
    }

    private record NoteRow(UUID id, UUID authorId, String body, Instant createdAt) {}

    private record HistoryRow(
            UUID id,
            @Nullable ExceptionStatus from,
            ExceptionStatus to,
            @Nullable String reason,
            @Nullable ResolutionCode resolutionCode,
            @Nullable String resolutionNote,
            @Nullable UUID actorId,
            Instant at) {}

    private RecordView record(ResultSet rs) throws SQLException {
        return new RecordView(
                rs.getObject("id", UUID.class),
                rs.getString("raw_reference"),
                rs.getString("normalized_reference"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("transaction_type"),
                rs.getString("status"),
                rs.getObject("transaction_date", LocalDate.class),
                instantOrNull(rs, "transaction_at"),
                rs.getObject("batch_id", UUID.class),
                rs.getLong("source_row_number"),
                rs.getBoolean("is_duplicate_in_source"),
                // JSONB returned as a JSON object, never as an escaped string.
                json.readTree(rs.getString("raw_values")));
    }

    static <E extends Enum<E>> @Nullable E enumOrNull(Class<E> type, @Nullable String name) {
        return name == null ? null : Enum.valueOf(type, name);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    static @Nullable Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static @Nullable Instant toInstant(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Timestamp ts -> ts.toInstant();
            case Instant i -> i;
            case java.time.OffsetDateTime odt -> odt.toInstant();
            default -> throw new IllegalArgumentException("Not a timestamp: " + value.getClass());
        };
    }

    private static void addIfPresent(Set<UUID> ids, @Nullable UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
