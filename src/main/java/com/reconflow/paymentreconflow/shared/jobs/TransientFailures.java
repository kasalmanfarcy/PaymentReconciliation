package com.reconflow.paymentreconflow.shared.jobs;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Is a failure worth another attempt (phase-M9 §2.3)? One classifier for both retry layers: the
 * in-process {@link TransientRetry} around a chunk's transaction, and the job queue's backoff.
 *
 * <ul>
 *   <li><b>Never</b>: a lost lease (someone else owns the work), an optimistic-lock conflict (it must
 *       reach the user as 409: retrying silently is the lost update {@code @Version} prevents), a
 *       constraint violation (a real duplicate stays a duplicate).
 *   <li><b>Yes</b>: SQLState {@code 40001} serialization failure, {@code 40P01} deadlock,
 *       {@code 55P03} lock not available, {@code 57014} statement timeout, {@code 57P01}–{@code 57P03}
 *       the server terminating or refusing the connection, any {@code 08xxx} connection exception;
 *       Spring's {@link TransientDataAccessException} family; no connection to start a transaction.
 * </ul>
 *
 * <p>The SQLState is checked <em>before</em> Spring's hierarchy, on the whole cause chain: Spring
 * translates {@code 08xxx} and {@code 57xxx} into {@code DataAccessResourceFailureException}, a
 * <em>non</em>-transient type, and a commit that fails on a dead connection arrives wrapped in a
 * {@code TransactionSystemException}. A lost connection is exactly what a retry on a fresh one fixes.
 *
 * <p>One case has no SQLState at all, found by {@code ImportTransientRetryIT} killing a real backend:
 * when the server drops the connection mid-transaction, the statement fails with {@code 57P01}, but
 * the rollback that follows fails too, on Hikari's closed proxy ({@code "Connection is closed"}, no
 * state), and Spring throws <em>that</em>, losing the original. A transaction whose rollback failed
 * on a dead connection never committed, so it is safe, and right, to retry.
 */
public final class TransientFailures {

    /** HikariCP's message for any call on a connection it has already closed (it sets no SQLState). */
    static final String POOL_CONNECTION_CLOSED = "Connection is closed";

    private static final Set<String> TRANSIENT_STATES =
            Set.of("40001", "40P01", "55P03", "57014", "57P01", "57P02", "57P03");

    private TransientFailures() {}

    public static boolean isTransient(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof LeaseLostException
                    || c instanceof OptimisticLockingFailureException
                    || c instanceof DataIntegrityViolationException) {
                return false;
            }
        }
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                String state = e.getSQLState();
                if (state != null && (TRANSIENT_STATES.contains(state) || state.startsWith("08"))) {
                    return true;
                }
                if (state == null && POOL_CONNECTION_CLOSED.equals(e.getMessage())) {
                    return true;
                }
            }
        }
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof TransientJobFailure
                    || c instanceof TransientDataAccessException
                    || c instanceof RecoverableDataAccessException
                    || c instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
