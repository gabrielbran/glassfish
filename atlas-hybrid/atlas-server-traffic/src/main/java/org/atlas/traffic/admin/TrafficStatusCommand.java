package org.atlas.traffic.admin;

import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandLock;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

/**
 * {@code asadmin atlas-traffic-status [target]}: state, time in that state, work in flight and, while
 * validating, progress and last failure.
 */
@Service(name = "atlas-traffic-status")
@PerLookup
@CommandLock(CommandLock.LockType.NONE)
@ExecuteOn({ RuntimeType.DAS, RuntimeType.INSTANCE })
@TargetType({ CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CLUSTERED_INSTANCE })
public class TrafficStatusCommand extends TrafficCommand {

    @Override
    void run(ActionReport report) {
        report.setMessage(describe(traffic.status()));
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }
}
