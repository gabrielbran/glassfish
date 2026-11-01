package org.atlas.traffic;

import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

/**
 * The readiness check HAProxy relies on. It is registered at the very first run level, before any
 * application is loaded, and reports DOWN ({@code STARTING}) until {@link TrafficService} attaches the
 * lifecycle: otherwise {@code /health/ready} would answer UP with no checks during startup, and the load
 * balancer would send requests to applications that are not loaded yet (findings H-10).
 */
final class TrafficReadiness implements HealthCheck {

    static final String NAME = "atlas-traffic";

    private static volatile TrafficLifecycle lifecycle;

    static void attach(TrafficLifecycle value) {
        lifecycle = value;
    }

    @Override
    public HealthCheckResponse call() {
        TrafficLifecycle current = lifecycle;
        if (current == null) {
            return new HealthCheckResponse(NAME, HealthCheckResponse.Status.DOWN,
                Optional.of(Map.of("state", TrafficState.STARTING.name())));
        }
        // Built directly (no provider lookup): the check runs with this bundle as context class loader
        return new HealthCheckResponse(NAME, current.ready() ? HealthCheckResponse.Status.UP : HealthCheckResponse.Status.DOWN,
            Optional.of(Map.copyOf(current.status())));
    }
}
