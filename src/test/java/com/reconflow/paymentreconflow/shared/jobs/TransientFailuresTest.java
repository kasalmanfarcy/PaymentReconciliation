package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** M9.3's classifier: which failures another attempt can fix (phase-M9 §2.3). */
class TransientFailuresTest {

    @ParameterizedTest(name = "SQLState {0} -> transient: {1}")
    @CsvSource({
        "40001, true", // serialization_failure
        "40P01, true", // deadlock_detected
        "55P03, true", // lock_not_available
        "57014, true", // query_canceled (statement_timeout)
        "57P01, true", // admin_shutdown: the server killed the backend
        "08006, true", // connection_failure
        "08003, true", // connection_does_not_exist
        "23505, false", // unique_violation: a real duplicate stays a duplicate
        "23502, false", // not_null_violation
        "22003, false", // numeric_value_out_of_range: bad data
        "42P01, false" // undefined_table: a bug
    })
    void classifiesBySqlState(String state, boolean expected) {
        assertThat(TransientFailures.isTransient(new SQLException("x", state))).isEqualTo(expected);
    }

    @Test
    void looksThroughSpringsNonTransientWrapperForAConnectionState() {
        // Spring translates 08xxx/57xxx to DataAccessResourceFailureException, a NON-transient type;
        // a commit on a dead connection arrives as a TransactionSystemException.
        var lost = new DataAccessResourceFailureException("closed", new SQLException("closed", "08003"));
        assertThat(TransientFailures.isTransient(lost)).isTrue();
        assertThat(TransientFailures.isTransient(new TransactionSystemException("commit failed", lost)))
                .isTrue();
    }

    @Test
    void aRollbackThatFailedOnThePoolsClosedConnectionIsTransient() {
        // What Spring throws when the server killed the backend mid-transaction (ImportTransientRetryIT).
        var rollbackFailed = new org.springframework.orm.jpa.JpaSystemException(new RuntimeException(
                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed")));
        assertThat(TransientFailures.isTransient(rollbackFailed)).isTrue();
        assertThat(TransientFailures.isTransient(new SQLException("some other driver error")))
                .isFalse();
    }

    @Test
    void springsTransientFamilyIsTransient() {
        assertThat(TransientFailures.isTransient(new CannotAcquireLockException("lock")))
                .isTrue();
        assertThat(TransientFailures.isTransient(new PessimisticLockingFailureException("deadlock")))
                .isTrue();
        assertThat(TransientFailures.isTransient(new CannotCreateTransactionException("no connection")))
                .isTrue();
        assertThat(TransientFailures.isTransient(new TransientJobFailure("storage 503", new RuntimeException())))
                .isTrue();
    }

    @Test
    void neverRetriesAConflictAUserMustSeeOrADuplicateOrALostLease() {
        // OptimisticLockingFailureException IS a TransientDataAccessException in Spring's hierarchy;
        // retrying it silently would be the lost update @Version exists to prevent.
        assertThat(TransientFailures.isTransient(new OptimisticLockingFailureException("stale")))
                .isFalse();
        assertThat(TransientFailures.isTransient(
                        new ObjectOptimisticLockingFailureException("Case", UUID.randomUUID())))
                .isFalse();
        assertThat(TransientFailures.isTransient(
                        new DataIntegrityViolationException("dup", new SQLException("dup", "23505"))))
                .isFalse();
        assertThat(TransientFailures.isTransient(new LeaseLostException(UUID.randomUUID(), "w")))
                .isFalse();
    }

    @Test
    void bugsAreNotTransient() {
        assertThat(TransientFailures.isTransient(new NullPointerException())).isFalse();
        assertThat(TransientFailures.isTransient(new IllegalStateException("x")))
                .isFalse();
    }
}
