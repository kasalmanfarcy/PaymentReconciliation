package com.reconflow.paymentreconflow.support;

import com.reconflow.paymentreconflow.shared.fault.FaultInjector;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The test-side {@link FaultInjector} (M9.3, M9.4): replaces production's no-op in every test
 * context (a component, not a per-test override, so no extra Spring context). Disarmed unless a test
 * scripts a point; tests must {@link #clear()} afterwards. Integration tests run one at a time and
 * the job worker is off in tests, so only the scenario a test drives reaches its points.
 */
@Primary
@Component
public class ScriptedFaultInjector implements FaultInjector {

    /**
     * "The JVM died here": an {@link Error}, which the job queue doesn't catch (it handles
     * exceptions), so the job stays RUNNING with its lease exactly as after a {@code kill -9}.
     */
    public static final class SimulatedCrash extends Error {
        SimulatedCrash(String point, int hit) {
            super("simulated crash at " + point + " (hit " + hit + ")");
        }
    }

    private record Script(int onHit, Runnable action, boolean repeat) {}

    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    /** Crash on the {@code hit}-th time (1-based) the point is reached. */
    public void crashAt(String point, int hit) {
        scripts.put(
                point,
                new Script(
                        hit,
                        () -> {
                            throw new SimulatedCrash(point, hit);
                        },
                        false));
        hits.remove(point);
    }

    /** Run {@code action} (throw a failure, kill a connection) on the {@code hit}-th time only. */
    public void onHit(String point, int hit, Runnable action) {
        scripts.put(point, new Script(hit, action, false));
        hits.remove(point);
    }

    /** Run {@code action} every time from the {@code hit}-th on (an outage that lasts). */
    public void fromHit(String point, int hit, Runnable action) {
        scripts.put(point, new Script(hit, action, true));
        hits.remove(point);
    }

    /** Forgets every hit count (scripts stay): to count a clean run's points from zero. */
    public void resetHits() {
        hits.clear();
    }

    /** How many times the point was reached since it was scripted (or since the last reset). */
    public int hits(String point) {
        AtomicInteger n = hits.get(point);
        return n == null ? 0 : n.get();
    }

    public void clear() {
        scripts.clear();
        hits.clear();
    }

    @Override
    public void at(String point) {
        int n = hits.computeIfAbsent(point, p -> new AtomicInteger()).incrementAndGet();
        Script script = scripts.get(point);
        if (script == null) {
            return;
        }
        if (n == script.onHit() || (script.repeat() && n > script.onHit())) {
            script.action().run();
        }
    }
}
