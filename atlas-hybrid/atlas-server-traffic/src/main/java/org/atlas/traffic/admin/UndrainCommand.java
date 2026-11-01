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
 * {@code asadmin atlas-undrain [target]}: back to VALIDATING. The instance becomes READY again only after
 * its validations pass; it never goes straight back to the load balancer.
 */
@Service(name = "atlas-undrain")
@PerLookup
@CommandLock(CommandLock.LockType.NONE)
@ExecuteOn({ RuntimeType.DAS, RuntimeType.INSTANCE })
@TargetType({ CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CLUSTERED_INSTANCE })
public class UndrainCommand extends TrafficCommand {

    @Override
    void run(ActionReport report) {
        traffic.undrain();
        report.setMessage(describe(traffic.status()));
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }
}
