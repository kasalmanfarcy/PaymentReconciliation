package com.reconflow.paymentreconflow.shared.fault;

/**
 * Named points where tests inject a crash, a failure or a side effect (M9.3, M9.4): "the worker died
 * right here". Production wires {@link #NONE}, a no-op lambda: no allocation, no logging, no test
 * code on the production path, and the JIT inlines it away.
 *
 * <p>Points are plain strings named {@code <area>.<moment>} ({@code import.after-chunk-commit});
 * {@link FaultPoints} lists them so tests and code can't drift apart.
 */
@FunctionalInterface
public interface FaultInjector {

    FaultInjector NONE = point -> {};

    void at(String point);
}
