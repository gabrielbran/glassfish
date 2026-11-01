package org.atlas.traffic.web;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared point between the traffic controller (HK2 service) and the {@link DrainFilter} that every web
 * application loads from default-web.xml. Both see the same class, exported by the Atlas traffic
 * bundle, so this static holder is the only coupling between them.
 */
public final class TrafficGate {

    /** What the filter does with a new request. */
    public enum Mode {
        /** Normal operation. */
        SERVE,
        /** Draining, grace period: serve, but ask the client to close keep-alive connections. */
        SERVE_AND_CLOSE,
        /** Draining after the grace period, or drained: answer 503 and close the connection. */
        REJECT
    }

    private static volatile TrafficGate current;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Set<Integer> excludedPorts;
    private volatile Mode mode = Mode.SERVE;

    /**
     * @param excludedPorts ports never affected nor counted, typically the admin listener
     */
    public TrafficGate(Set<Integer> excludedPorts) {
        this.excludedPorts = Set.copyOf(excludedPorts);
    }

    /** Makes this gate the one used by every {@link DrainFilter}. */
    public static void install(TrafficGate gate) {
        current = gate;
    }

    /** @return the installed gate, {@code null} when the traffic module is not running */
    public static TrafficGate current() {
        return current;
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    /** @return HTTP requests currently being processed by web applications on traffic ports */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Health endpoints and admin ports are never rejected nor counted: the load balancer must keep
     * seeing the readiness status, and administrators must keep their tools while draining.
     */
    public boolean excluded(int localPort, String requestUri) {
        return excludedPorts.contains(localPort) || requestUri.startsWith("/health");
    }

    void enter() {
        inFlight.incrementAndGet();
    }

    void exit() {
        inFlight.decrementAndGet();
    }
}
