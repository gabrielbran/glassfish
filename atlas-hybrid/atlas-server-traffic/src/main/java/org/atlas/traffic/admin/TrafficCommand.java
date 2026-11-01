package org.atlas.traffic.admin;

import jakarta.inject.Inject;

import java.util.Map;

import org.atlas.traffic.TrafficService;
import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ServerEnvironment;

/**
 * Base of the traffic commands. They are declared {@code @ExecuteOn({DAS, INSTANCE})}: GlassFish runs
 * them on the DAS and replicates them to the instances of the target. The DAS only does the work when
 * it is itself the target ({@code server}); for a cluster or an instance, each instance acts on itself.
 */
abstract class TrafficCommand implements AdminCommand {

    @Param(primary = true, optional = true, defaultValue = "server")
    String target;

    @Inject
    ServerEnvironment environment;

    @Inject
    TrafficService traffic;

    @Override
    public final void execute(AdminCommandContext context) {
        ActionReport report = context.getActionReport();
        if (environment.isDas() && !"server".equals(target)) {
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
            return;
        }
        try {
            run(report);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.setMessage("Interrupted");
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
        } catch (RuntimeException e) {
            report.setMessage(e.getMessage());
            report.setFailureCause(e);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
        }
    }

    abstract void run(ActionReport report) throws InterruptedException;

    String describe(Map<String, Object> status) {
        StringBuilder text = new StringBuilder(environment.getInstanceName()).append(":");
        status.forEach((key, value) -> text.append(' ').append(key).append('=').append(value).append(';'));
        return text.toString();
    }
}
