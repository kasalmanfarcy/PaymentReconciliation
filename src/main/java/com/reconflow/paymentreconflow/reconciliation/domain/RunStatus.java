package com.reconflow.paymentreconflow.reconciliation.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The reconciliation run state machine (reconciliation-rules.md §7.2), in exactly one place. Values
 * match the CHECK on {@code reconciliation_run.status}; anything not in {@link #ALLOWED} is rejected
 * with {@code 409 invalid-transition}. Only {@code RunLifecycle} moves a run between states.
 *
 * <p>{@code RUNNING → RUNNING} (a job re-delivered after a crash) is deliberately absent: resuming
 * from the checkpoint isn't a status change. {@code PENDING → FAILED} is there for the same reason
 * imports have {@code RECEIVED → FAILED}: the job can run out of attempts before the run ever
 * starts (every claim failed transiently).
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED;

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(RUNNING, FAILED),
            RUNNING, EnumSet.of(COMPLETED, FAILED),
            FAILED, EnumSet.of(PENDING), // explicit retry only (M6.12)
            COMPLETED, EnumSet.noneOf(RunStatus.class));

    public boolean canMoveTo(RunStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** PENDING and RUNNING: at most one such run per organization and scope (V5's partial index). */
    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
