package com.reconflow.paymentreconflow.shared.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The in-process retry layer (M9.3): re-runs one unit of work, a whole transaction, after a
 * transient database failure (a deadlock victim, a serialization failure, a dropped connection),
 * with exponential backoff and jitter: 100 ms, 200 ms, 400 ms (±50 ms), then gives up and lets the
 * failure reach the job queue, the second, slower layer (backoff in minutes). Nothing else retries:
 * HTTP requests don't, so a user's 409 always reaches the user.
 *
 * <p><b>The retry must wrap the transaction, never run inside it.</b> After an error PostgreSQL marks
 * the transaction aborted and refuses every further statement ({@code 25P02}); only a rollback and a
 * new transaction can succeed. So {@link #call} refuses to start inside a transaction, and callers
 * pass the {@code TransactionTemplate} call itself as the work.
 *
 * <p>Programmatic (Spring Framework 7's {@link RetryTemplate}) rather than {@code @Retryable}: the
 * chunk transactions are {@code TransactionTemplate} calls inside one method, which a proxy-based
 * annotation can't reach (self-invocation), and the wrapping order is then visible in the code.
 *
 * <p><b>Why retrying a commit is safe.</b> A connection lost <em>during</em> {@code COMMIT} leaves it
 * unknown whether the commit happened. Retrying is still correct because every unit retried here is
 * idempotent: rows insert {@code ON CONFLICT DO NOTHING}, and each checkpoint advance is a
 * compare-and-set that recognises "already done" (the import's chunk, the run's day), so a commit
 * that did land is not applied twice.
 */
@Component
public class TransientRetry {

    static final int MAX_RETRIES = 3;

    private static final Logger log = LoggerFactory.getLogger(TransientRetry.class);

    private final RetryTemplate template;
    private final MeterRegistry registry;

    TransientRetry(MeterRegistry registry) {
        this.registry = registry;
        this.template = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(MAX_RETRIES)
                .delay(Duration.ofMillis(100))
                .multiplier(2)
                .jitter(Duration.ofMillis(50))
                .maxDelay(Duration.ofSeconds(1))
                .predicate(TransientFailures::isTransient)
                .build());
        this.template.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> work, RetryState state) {
                Throwable last = state.getLastException();
                log.warn(
                        "Retrying {} after a transient failure: {}",
                        work.getName(),
                        last == null ? "?" : last.toString());
                // The operation names are a fixed set (import.chunk, reconcile.day, ...): a safe tag.
                registry.counter("reconflow.db.retries", "operation", work.getName())
                        .increment();
            }
        });
    }

    /**
     * Calls {@code work} (which opens its own transaction), retrying transient failures. Returns its
     * result or throws its last failure unchanged, so the job queue classifies it as before.
     *
     * @param operation a fixed name for logs and the {@code reconflow.db.retries} metric tag
     */
    public <T> T call(String operation, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TransientRetry must wrap the transaction, not run inside one: an aborted transaction can't be retried");
        }
        try {
            return template.execute(new Retryable<>() {
                @Override
                public T execute() {
                    return work.get();
                }

                @Override
                public String getName() {
                    return operation;
                }
            });
        } catch (RetryException e) {
            Throwable last = e.getLastException();
            if (last instanceof RuntimeException r) {
                throw r;
            }
            if (last instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e);
        }
    }

    /** As {@link #call}, for work with no result. */
    public void run(String operation, Runnable work) {
        call(operation, () -> {
            work.run();
            return null;
        });
    }
}
