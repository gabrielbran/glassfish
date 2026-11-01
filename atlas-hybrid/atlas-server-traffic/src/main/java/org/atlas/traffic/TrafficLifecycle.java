package org.atlas.traffic;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.atlas.traffic.web.TrafficGate;
import org.atlas.traffic.web.TrafficGate.Mode;

/**
 * Traffic state machine of one instance, independent of GlassFish so it can be tested alone.
 *
 * <pre>
 * STARTING --server started--> VALIDATING --N validations in a row--> READY
 * READY/VALIDATING --drain--> DRAINING --nothing in flight--> DRAINED --undrain--> VALIDATING
 * </pre>
 * {@link #tick()} is called periodically (every second) by the scheduler; {@link #drain} blocks the
 * calling admin command until the instance is drained or the timeout expires.
 */
final class TrafficLifecycle {

    private static final Logger LOG = Logger.getLogger(TrafficLifecycle.class.getName());

    /** Result of one validation round: {@code null} when everything passed, otherwise the reason. */
    interface Validator {
        String validate();
    }

    /** Releases idle connections of the JDBC pools once drained; returns the pools flushed. */
    interface PoolFlusher {
        List<String> flush();
    }

    /** Lets tests run without real waiting. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final TrafficGate gate;
    private final BooleanSupplier serverStarted;
    private final Validator validator;
    private final Supplier<InFlight> inFlight;
    private final PoolFlusher flusher;
    private final Sleeper sleeper;
    private final int requiredSuccesses;

    private volatile TrafficState state = TrafficState.STARTING;
    private volatile Instant since = Instant.now();
    private volatile String lastValidationFailure;
    private volatile int consecutiveSuccesses;

    TrafficLifecycle(TrafficGate gate, BooleanSupplier serverStarted, Validator validator, Supplier<InFlight> inFlight,
        PoolFlusher flusher, Sleeper sleeper, int requiredSuccesses) {
        this.gate = gate;
        this.serverStarted = serverStarted;
        this.validator = validator;
        this.inFlight = inFlight;
        this.flusher = flusher;
        this.sleeper = sleeper;
        this.requiredSuccesses = requiredSuccesses;
    }

    TrafficState state() {
        return state;
    }

    boolean ready() {
        return state == TrafficState.READY;
    }

    /** Periodic step: leaves STARTING once the server started, runs one validation round while VALIDATING. */
    synchronized void tick() {
        if (state == TrafficState.STARTING && serverStarted.getAsBoolean()) {
            enter(TrafficState.VALIDATING, "server started");
        }
        if (state != TrafficState.VALIDATING) {
            return;
        }
        String failure;
        try {
            failure = validator.validate();
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (failure == null) {
            consecutiveSuccesses++;
            lastValidationFailure = null;
            if (consecutiveSuccesses >= requiredSuccesses) {
                enter(TrafficState.READY, consecutiveSuccesses + " validations passed in a row");
            }
        } else {
            if (!failure.equals(lastValidationFailure)) {
                String reason = failure;
                LOG.warning(() -> "Atlas traffic: validation failed, instance stays out of the load balancer: " + reason);
            }
            consecutiveSuccesses = 0;
            lastValidationFailure = failure;
        }
    }

    /**
     * Stops new traffic and waits for the work in flight.
     * <ol>
     * <li>readiness goes DOWN at once; during {@code grace} requests are still served, with
     * {@code Connection: close}, while the load balancer notices;</li>
     * <li>after the grace period new requests get 503;</li>
     * <li>waits until no HTTP request, JTA transaction or JDBC connection is in use, at most {@code timeout};</li>
     * <li>optionally flushes the JDBC pools, releasing the database sessions, and becomes DRAINED.</li>
     * </ol>
     */
    DrainResult drain(Duration grace, Duration timeout, boolean flushPools) throws InterruptedException {
        Instant start = Instant.now();
        synchronized (this) {
            if (state == TrafficState.DRAINED) {
                return new DrainResult(true, state, Duration.ZERO, inFlight.get(), List.of());
            }
            gate.mode(Mode.SERVE_AND_CLOSE);
            enter(TrafficState.DRAINING, "drain requested (grace " + grace.toSeconds() + " s, timeout " + timeout.toSeconds() + " s)");
        }
        sleeper.sleep(grace.toMillis());
        gate.mode(Mode.REJECT);

        Instant deadline = start.plus(timeout);
        InFlight current = inFlight.get();
        while (!current.idle()) {
            if (Instant.now().isAfter(deadline)) {
                InFlight remaining = current;
                LOG.warning(() -> "Atlas traffic: drain timed out, still in flight: " + remaining);
                return new DrainResult(false, state, Duration.between(start, Instant.now()), remaining, List.of());
            }
            sleeper.sleep(200);
            current = inFlight.get();
        }
        List<String> flushed = flushPools ? flusher.flush() : List.of();
        synchronized (this) {
            if (state == TrafficState.DRAINING) {
                enter(TrafficState.DRAINED, "nothing in flight" + (flushed.isEmpty() ? "" : ", pools flushed " + flushed));
            }
        }
        return new DrainResult(true, state, Duration.between(start, Instant.now()), current, flushed);
    }

    /** Back to VALIDATING: the instance must earn READY again, it never jumps straight to it. */
    synchronized void undrain() {
        gate.mode(Mode.SERVE);
        enter(TrafficState.VALIDATING, "undrain requested");
    }

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", state.name());
        status.put("since", since.toString());
        status.put("inFlight", inFlight.get().toString());
        if (state == TrafficState.VALIDATING) {
            status.put("validations", consecutiveSuccesses + "/" + requiredSuccesses);
            if (lastValidationFailure != null) {
                status.put("lastFailure", lastValidationFailure);
            }
        }
        return status;
    }

    private void enter(TrafficState next, String reason) {
        TrafficState previous = state;
        state = next;
        since = Instant.now();
        consecutiveSuccesses = 0;
        if (next != TrafficState.VALIDATING) {
            lastValidationFailure = null;
        }
        LOG.info(() -> "Atlas traffic: " + previous + " -> " + next + " (" + reason + ")");
    }
}
