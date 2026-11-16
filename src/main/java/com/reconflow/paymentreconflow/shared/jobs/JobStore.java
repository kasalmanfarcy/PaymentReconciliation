package com.reconflow.paymentreconflow.shared.jobs;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every SQL statement on the {@code job} table, and nothing else touches it ({@code shared.jobs}
 * owns it). The table has no row-level security on purpose (V4): the claim must see every
 * organization's jobs.
 *
 * <p>All times are the database's {@code now()}, never a JVM clock: workers on several machines
 * then agree on whether a lease has expired, whatever their clock skew.
 *
 * <p>Every write after the claim carries {@code AND locked_by = ? AND status = 'RUNNING'}: a worker
 * can only change a job it still holds.
 */
@Component
class JobStore {

    private static final String RETURNING =
            " RETURNING id, organization_id, type, payload::text, attempts, max_attempts";

    // FOR UPDATE locks the chosen row; SKIP LOCKED makes concurrent claimers skip it instead of
    // waiting, so N workers get N different jobs at once. The lock ends at commit (this statement's
    // own transaction); from then on the lease, not the lock, says who owns the job.
    // The per-organization count is a soft cap: two claimers at the same instant can both see 1
    // running and both claim (SKIP LOCKED locks rows, not predicates). Fine for fairness; a hard cap
    // would take pg_advisory_xact_lock(hashtext(organization_id::text)) in the claim.
    /** A claimed job and how long it had been runnable ({@code now() - run_after}), for the metric. */
    record Claimed(Job job, Duration waited) {}

    static final String CLAIM = """
            UPDATE job
               SET status = 'RUNNING', locked_by = ?, lease_until = now() + make_interval(secs => ?),
                   attempts = attempts + 1, updated_at = now()
             WHERE id = (
                   SELECT j.id FROM job j
                    WHERE j.status = 'QUEUED' AND j.run_after <= now()
                      %s
                      AND (SELECT count(*) FROM job r
                            WHERE r.organization_id = j.organization_id AND r.status = 'RUNNING') < ?
                    ORDER BY j.created_at, j.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
            """ + RETURNING + ", extract(epoch FROM now() - run_after)::float8 AS waited_s";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    JobStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(UUID id, UUID organizationId, String type, Map<String, ?> payload) {
        jdbc.update(
                "INSERT INTO job (id, organization_id, type, payload) VALUES (?, ?, ?, ?::jsonb)",
                id,
                organizationId,
                type,
                json.writeValueAsString(payload));
    }

    /**
     * One job of one organization. The table has no RLS (the claim is cross-tenant), so the
     * organization filter here is the only thing keeping one tenant from reading another's job.
     */
    Optional<JobStatus> find(UUID organizationId, UUID jobId) {
        return jdbc
                .query(
                        "SELECT id, organization_id, type, status, payload::text, created_at, updated_at FROM job"
                                + " WHERE id = ? AND organization_id = ?",
                        (rs, n) -> new JobStatus(
                                rs.getObject("id", UUID.class),
                                rs.getObject("organization_id", UUID.class),
                                rs.getString("type"),
                                rs.getString("status"),
                                json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {}),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant()),
                        jobId,
                        organizationId)
                .stream()
                .findFirst();
    }

    /**
     * Is there a QUEUED or RUNNING job of this type whose payload names {@code subjectId} under
     * {@code payloadKey}? For M9.1's consistency check; runs for a handful of stale rows only, so
     * the JSONB lookup needs no index.
     */
    boolean hasLiveJob(UUID organizationId, String type, String payloadKey, UUID subjectId) {
        Boolean live = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM job WHERE organization_id = ? AND type = ?"
                        + " AND payload ->> ? = ? AND status IN ('QUEUED', 'RUNNING'))",
                Boolean.class,
                organizationId,
                type,
                payloadKey,
                subjectId.toString());
        return Boolean.TRUE.equals(live);
    }

    /**
     * Claims the oldest runnable job, if any. {@code onlyOrganizations} narrows the claim to some
     * organizations; production passes null. It exists for tests, which share one database and
     * must not run each other's jobs.
     */
    Optional<Claimed> claim(
            String workerId, Duration lease, int perOrgMaxRunning, @Nullable Collection<UUID> onlyOrganizations) {
        String sql = CLAIM.formatted(onlyOrganizations == null ? "" : "AND j.organization_id = ANY(?)");
        List<Claimed> claimed = jdbc.query(
                (Connection c) -> {
                    PreparedStatement ps = c.prepareStatement(sql);
                    int i = 1;
                    ps.setString(i++, workerId);
                    ps.setDouble(i++, seconds(lease));
                    if (onlyOrganizations != null) {
                        Array orgs = c.createArrayOf("uuid", onlyOrganizations.toArray());
                        ps.setArray(i++, orgs);
                    }
                    ps.setInt(i, perOrgMaxRunning);
                    return ps;
                },
                (rs, n) -> new Claimed(
                        map(rs, workerId),
                        Duration.ofMillis(Math.max(0, Math.round(rs.getDouble("waited_s") * 1000)))));
        return claimed.stream().findFirst();
    }

    /** Heartbeat and fence: extends the lease if, and only if, this worker still holds the job. */
    boolean extendLease(UUID jobId, String workerId, Duration lease) {
        return jdbc.update(
                        "UPDATE job SET lease_until = now() + make_interval(secs => ?), updated_at = now()"
                                + " WHERE id = ? AND locked_by = ? AND status = 'RUNNING'",
                        seconds(lease),
                        jobId,
                        workerId)
                == 1;
    }

    boolean markDone(UUID jobId, String workerId) {
        return jdbc.update(
                        "UPDATE job SET status = 'DONE', lease_until = NULL, updated_at = now()"
                                + " WHERE id = ? AND locked_by = ? AND status = 'RUNNING'",
                        jobId,
                        workerId)
                == 1;
    }

    /** Back in the queue after a transient failure, not before {@code delay} has passed. */
    boolean reschedule(UUID jobId, String workerId, Duration delay, String error) {
        return jdbc.update(
                        "UPDATE job SET status = 'QUEUED', locked_by = NULL, lease_until = NULL,"
                                + " run_after = now() + make_interval(secs => ?), last_error = ?, updated_at = now()"
                                + " WHERE id = ? AND locked_by = ? AND status = 'RUNNING'",
                        seconds(delay),
                        truncate(error),
                        jobId,
                        workerId)
                == 1;
    }

    boolean markFailed(UUID jobId, String workerId, String error) {
        return jdbc.update(
                        "UPDATE job SET status = 'FAILED', lease_until = NULL, last_error = ?, updated_at = now()"
                                + " WHERE id = ? AND locked_by = ? AND status = 'RUNNING'",
                        truncate(error),
                        jobId,
                        workerId)
                == 1;
    }

    /**
     * Graceful shutdown: hand the job back at once, without spending an attempt, so another worker
     * resumes it from its checkpoint now instead of after the lease expires.
     */
    boolean release(UUID jobId, String workerId) {
        return jdbc.update(
                        "UPDATE job SET status = 'QUEUED', locked_by = NULL, lease_until = NULL, run_after = now(),"
                                + " attempts = greatest(attempts - 1, 0), last_error = 'released on shutdown',"
                                + " updated_at = now() WHERE id = ? AND locked_by = ? AND status = 'RUNNING'",
                        jobId,
                        workerId)
                == 1;
    }

    /**
     * The handler can't start yet (JobDeferredException): back in the queue after {@code delay},
     * without spending the attempt this claim counted.
     */
    boolean defer(UUID jobId, String workerId, Duration delay, String reason) {
        return jdbc.update(
                        "UPDATE job SET status = 'QUEUED', locked_by = NULL, lease_until = NULL,"
                                + " run_after = now() + make_interval(secs => ?), attempts = greatest(attempts - 1, 0),"
                                + " last_error = ?, updated_at = now() WHERE id = ? AND locked_by = ? AND status = 'RUNNING'",
                        seconds(delay),
                        truncate("deferred: " + reason),
                        jobId,
                        workerId)
                == 1;
    }

    /** Expired leases with attempts left go back to the queue; returns their ids. */
    List<UUID> requeueExpired() {
        return jdbc.queryForList("""
                UPDATE job SET status = 'QUEUED', locked_by = NULL, lease_until = NULL, run_after = now(),
                       last_error = 'lease expired', updated_at = now()
                 WHERE status = 'RUNNING' AND lease_until < now() AND attempts < max_attempts
                RETURNING id
                """, UUID.class);
    }

    /** Expired leases with no attempts left: candidates to fail, each re-checked under a lock. */
    List<Job> expiredExhausted() {
        return jdbc.query(
                "SELECT id, organization_id, type, payload::text, attempts, max_attempts, locked_by FROM job"
                        + " WHERE status = 'RUNNING' AND lease_until < now() AND attempts >= max_attempts",
                (rs, n) -> map(rs, rs.getString("locked_by")));
    }

    /** Inside the caller's transaction: locks the job if it is still expired and exhausted. */
    boolean lockExpiredExhausted(UUID jobId) {
        return !jdbc.queryForList(
                        "SELECT id FROM job WHERE id = ? AND status = 'RUNNING' AND lease_until < now()"
                                + " AND attempts >= max_attempts FOR UPDATE SKIP LOCKED",
                        UUID.class,
                        jobId)
                .isEmpty();
    }

    void markFailedBySweeper(UUID jobId, String error) {
        jdbc.update(
                "UPDATE job SET status = 'FAILED', lease_until = NULL, last_error = ?, updated_at = now()"
                        + " WHERE id = ? AND status = 'RUNNING'",
                truncate(error),
                jobId);
    }

    /** QUEUED jobs per type, for the {@code reconflow.jobs.queued} gauge (a partial-index count). */
    Map<String, Long> countQueuedByType() {
        Map<String, Long> counts = new java.util.HashMap<>();
        jdbc.query("SELECT type, count(*) FROM job WHERE status = 'QUEUED' GROUP BY type", rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        });
        return counts;
    }

    /**
     * Retention: deletes up to {@code limit} jobs in {@code status} last updated before
     * {@code olderThan} ago. Bounded per statement so a first run on a big table doesn't hold one
     * huge delete (and its WAL) in a single transaction; the caller loops.
     */
    int deleteFinishedBefore(String status, Duration olderThan, int limit) {
        return jdbc.update(
                "DELETE FROM job WHERE id IN (SELECT id FROM job WHERE status = ?"
                        + " AND updated_at < now() - make_interval(secs => ?) LIMIT ?)",
                status,
                seconds(olderThan),
                limit);
    }

    private Job map(ResultSet rs, String workerId) throws SQLException {
        Map<String, Object> payload = json.readValue(rs.getString("payload"), new TypeReference<>() {});
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("type"),
                payload,
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                workerId);
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }

    private static String truncate(String error) {
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }
}
