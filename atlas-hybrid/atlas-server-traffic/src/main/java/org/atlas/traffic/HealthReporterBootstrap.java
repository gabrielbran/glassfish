package org.atlas.traffic;

import jakarta.inject.Inject;

import java.util.Set;
import java.util.logging.Logger;

import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.runlevel.RunLevel;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.glassfish.internal.api.InitRunLevel;
import org.glassfish.microprofile.health.HealthCheckInfo;
import org.glassfish.microprofile.health.HealthReporter;
import org.jvnet.hk2.annotations.Service;

/**
 * Works around a GlassFish 8.0.5 defect (findings H-9): on clustered instances the MicroProfile Health
 * service that registers {@link HealthReporter} does not start, so {@code /health/ready} answers 500 and
 * the applications' own health checks are never registered.
 * <p>
 * At the first run level, before any application is loaded, this registers the reporter exactly as
 * GlassFish's own {@code HealthService} does when it starts; if that service does start, it finds and
 * reuses this one. It also registers the Atlas readiness check right away, reporting DOWN until the
 * traffic lifecycle starts.
 */
@Service
@RunLevel(InitRunLevel.VAL)
public class HealthReporterBootstrap implements PostConstruct {

    private static final Logger LOG = Logger.getLogger(HealthReporterBootstrap.class.getName());

    @Inject
    ServiceLocator locator;

    @Override
    public void postConstruct() {
        if (locator.getService(HealthReporter.class) == null) {
            ServiceLocatorUtilities.addClasses(locator, true, HealthReporter.class);
            LOG.info("Atlas traffic: MicroProfile Health reporter registered (GlassFish did not register it on this instance)");
        }
        // Readiness DOWN from the first moment, so no load arrives before validation (findings H-10)
        locator.getService(HealthReporter.class).addHealthCheck(TrafficReadiness.NAME,
            new HealthCheckInfo(new TrafficReadiness(), Set.of(HealthCheckInfo.Kind.READY)));
    }
}
