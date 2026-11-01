package org.atlas.traffic.admin;

import java.time.Duration;

import org.atlas.traffic.DrainResult;
import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.CommandLock;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

/**
 * {@code asadmin atlas-drain [--grace 5] [--timeout 120] [--flushpools true] [target]}: readiness DOWN,
 * grace period for the load balancer, then 503 for new requests while HTTP requests, JTA transactions
 * and JDBC connections in flight finish. Blocks until DRAINED or timeout (exit code FAILURE, listing
 * what is still running).
 */
@Service(name = "atlas-drain")
@PerLookup
@CommandLock(CommandLock.LockType.NONE)
@ExecuteOn({ RuntimeType.DAS, RuntimeType.INSTANCE })
@TargetType({ CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CLUSTERED_INSTANCE })
public class DrainCommand extends TrafficCommand {

    @Param(optional = true, defaultValue = "5")
    int grace;

    @Param(optional = true, defaultValue = "120")
    int timeout;

    @Param(optional = true, defaultValue = "true")
    boolean flushpools;

    @Override
    void run(ActionReport report) throws InterruptedException {
        DrainResult result = traffic.drain(Duration.ofSeconds(grace), Duration.ofSeconds(timeout), flushpools);
        String message = environment.getInstanceName() + ": " + result.state() + " after " + result.duration().toMillis()
            + " ms; in flight: " + result.remaining() + (result.flushedPools().isEmpty() ? "" : "; pools flushed: " + result.flushedPools());
        report.setMessage(message);
        report.setActionExitCode(result.drained() ? ActionReport.ExitCode.SUCCESS : ActionReport.ExitCode.FAILURE);
    }
}
