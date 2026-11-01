package org.atlas.traffic;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Resource;
import com.sun.enterprise.config.serverbeans.ResourcePool;
import com.sun.enterprise.resource.pool.PoolManager;
import com.sun.enterprise.resource.pool.PoolStatus;
import com.sun.enterprise.transaction.api.JavaEETransactionManager;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.atlas.traffic.web.TrafficGate;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.naming.SimpleJndiName;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.PreDestroy;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.runlevel.RunLevel;
import org.glassfish.internal.api.PostStartupRunLevel;
import org.glassfish.internal.data.ApplicationInfo;
import org.glassfish.internal.data.ApplicationRegistry;
import org.glassfish.microprofile.health.HealthReport;
import org.glassfish.microprofile.health.HealthReporter;
import org.glassfish.resourcebase.resources.api.PoolInfo;
import org.jvnet.hk2.annotations.Service;

/**
 * GlassFish side of the traffic lifecycle (ADR-011). Starts with the server, publishes a readiness
 * check named {@value #HEALTH_CHECK} in the server's MicroProfile Health ({@code /health/ready}, the
 * URL HAProxy probes), and measures the work in flight:
 * <ul>
 * <li>HTTP: requests counted by {@link org.atlas.traffic.web.DrainFilter} (default-web.xml);</li>
 * <li>JTA: active transactions of the transaction manager, which covers EJB business methods, timers and
 * MDBs. GlassFish only tracks them when transaction-service monitoring is not OFF at startup;</li>
 * <li>JDBC: connections in use per initialized connection pool.</li>
 * </ul>
 * Validation before READY: every application loaded, every application readiness check UP, and the
 * configured read-only database queries passing {@code atlas.traffic.validation.successes} times in a
 * row (default 3, one round per second).
 */
@Service(name = "atlas-traffic")
@RunLevel(PostStartupRunLevel.VAL)
public class TrafficService implements PostConstruct, PreDestroy {

    static final String HEALTH_CHECK = TrafficReadiness.NAME;
    private static final Logger LOG = Logger.getLogger(TrafficService.class.getName());

    @Inject
    ServiceLocator locator;

    @Inject
    ServerEnvironment environment;

    @Inject
    ApplicationRegistry applications;

    @Inject
    Domain domain;

    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    Config config;

    private TrafficGate gate;
    private TrafficLifecycle lifecycle;
    private ScheduledExecutorService scheduler;
    private boolean transactionsCounted;

    @Override
    public void postConstruct() {
        gate = new TrafficGate(excludedPorts());
        ReadOnlyValidation database = new ReadOnlyValidation(ReadOnlyValidation.fromSystemProperties(), TrafficService::dataSource);
        // The transaction manager decides at startup whether it tracks active transactions
        transactionsCounted = !"OFF".equalsIgnoreCase(
            config.getMonitoringService().getModuleMonitoringLevels().getTransactionService());
        lifecycle = new TrafficLifecycle(gate, () -> environment.getStatus() == ServerEnvironment.Status.started,
            () -> validate(database), this::inFlight, this::flushPools, Thread::sleep,
            Integer.getInteger("atlas.traffic.validation.successes", 3));
        TrafficGate.install(gate);
        TrafficReadiness.attach(lifecycle);

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "atlas-traffic");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                lifecycle.tick();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Atlas traffic: lifecycle step failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        LOG.info(() -> "Atlas traffic started on " + environment.getInstanceName() + ": readiness check '" + HEALTH_CHECK
            + "', read-only validations " + database.checks() + ", excluded ports " + excludedPorts()
            + (transactionsCounted ? "" : ", JTA transactions NOT counted (transaction-service monitoring is OFF)"));
    }

    @Override
    public void preDestroy() {
        scheduler.shutdownNow();
        TrafficGate.install(null);
        TrafficReadiness.attach(null);
    }

    /** Blocks until drained or timed out, see {@link TrafficLifecycle#drain}. */
    public DrainResult drain(java.time.Duration grace, java.time.Duration timeout, boolean flushPools)
        throws InterruptedException {
        return lifecycle.drain(grace, timeout, flushPools);
    }

    public void undrain() {
        lifecycle.undrain();
    }

    public Map<String, Object> status() {
        return lifecycle.status();
    }

    // ---- readiness -----------------------------------------------------------------------------

    // ---- validation ----------------------------------------------------------------------------

    private String validate(ReadOnlyValidation database) {
        for (String name : applications.getAllApplicationNames()) {
            ApplicationInfo info = applications.get(name);
            if (info != null && !info.isLoaded()) {
                return "application " + name + " is not loaded";
            }
        }
        HealthReporter reporter = locator.getService(HealthReporter.class);
        if (reporter != null) {
            HealthReport report = reporter.getReport(HealthReporter.ReportKind.READY);
            List<String> down = report.checks().stream()
                .filter(check -> !HEALTH_CHECK.equals(check.getName()))
                .filter(check -> check.getStatus() == HealthCheckResponse.Status.DOWN)
                .map(HealthCheckResponse::getName)
                .toList();
            if (!down.isEmpty()) {
                return "application readiness checks DOWN: " + down;
            }
        }
        return database.run();
    }

    private static DataSource dataSource(String jndiName) {
        try {
            return (DataSource) new InitialContext().lookup(jndiName);
        } catch (NamingException e) {
            throw new IllegalStateException("JDBC resource " + jndiName + " not found: " + e.getMessage(), e);
        }
    }

    // ---- work in flight ------------------------------------------------------------------------

    private InFlight inFlight() {
        int transactions = -1;
        if (transactionsCounted) {
            JavaEETransactionManager manager = locator.getService(JavaEETransactionManager.class);
            transactions = manager == null ? -1 : manager.getActiveTransactions().size();
        }
        Map<String, Integer> jdbc = new LinkedHashMap<>();
        PoolManager pools = locator.getService(PoolManager.class);
        if (pools != null) {
            for (String pool : poolNames()) {
                PoolStatus status = pools.getPoolStatus(new PoolInfo(SimpleJndiName.of(pool)));
                if (status != null) {
                    jdbc.put(pool, status.getNumConnUsed());
                }
            }
        }
        return new InFlight(gate.inFlight(), transactions, jdbc);
    }

    /** Releases the idle connections of the initialized pools, so the database sessions end before a stop. */
    private List<String> flushPools() {
        List<String> flushed = new ArrayList<>();
        PoolManager pools = locator.getService(PoolManager.class);
        if (pools == null) {
            return flushed;
        }
        for (String pool : poolNames()) {
            PoolInfo info = new PoolInfo(SimpleJndiName.of(pool));
            if (pools.getPool(info) != null) {
                try {
                    pools.flushConnectionPool(info);
                    flushed.add(pool);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Atlas traffic: could not flush pool " + pool, e);
                }
            }
        }
        return flushed;
    }

    private List<String> poolNames() {
        List<String> names = new ArrayList<>();
        for (Resource resource : domain.getResources().getResources()) {
            if (resource instanceof ResourcePool pool) {
                names.add(pool.getName());
            }
        }
        return names;
    }

    /** Admin listener (ASADMIN_LISTENER_PORT on instances, 4848 on the DAS) plus atlas.traffic.excludedPorts. */
    private static Set<Integer> excludedPorts() {
        Set<Integer> ports = new HashSet<>();
        ports.add(Integer.parseInt(System.getProperty("ASADMIN_LISTENER_PORT", "4848")));
        for (String port : System.getProperty("atlas.traffic.excludedPorts", "").split(",")) {
            if (!port.isBlank()) {
                ports.add(Integer.parseInt(port.strip()));
            }
        }
        return ports;
    }
}
