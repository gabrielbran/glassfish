package org.atlas.traffic.demo;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * The application's own readiness check (standard MicroProfile Health). While DOWN, the Atlas traffic
 * lifecycle does not let the instance become READY.
 */
@Readiness
@ApplicationScoped
public class DemoReadiness implements HealthCheck {

    private volatile boolean up = true;

    void up(boolean value) {
        up = value;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("demo-application").status(up).build();
    }
}
