package org.atlas.plugin.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.atlas.flow.api.FlowEngine;
import org.atlas.flow.api.FlowResult;
import org.atlas.plugin.api.Plugin;
import org.atlas.plugin.api.PluginRequest;
import org.atlas.plugin.api.PluginResult;
import org.atlas.plugin.orchestrator.FlowModel.Definition;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

/**
 * In-memory flow engine. It is itself a plugin (operation {@code run}), so a flow can invoke another
 * flow as an activity.
 * <p>
 * Definitions live in memory: they are lost when this bundle restarts, so applications define their
 * flows again when {@link #flows()} does not list them.
 * <p>
 * Parallel and asynchronous activities run on the calling application's
 * {@code java:comp/DefaultManagedExecutorService} (Jakarta Concurrency), so they keep its naming and
 * class loading context; a private pool is used only for non Jakarta EE callers.
 */
@Component(service = { FlowEngine.class, Plugin.class }, property = {
    Plugin.NAME_PROPERTY + "=orchestrator",
    Plugin.DESCRIPTION_PROPERTY + "=Orquestador en memoria: actividades en secuencia, en paralelo y disparar-y-olvidar,"
        + " reglas de aborto, rollback JTA y compensaciones" })
public class OrchestratorPlugin implements FlowEngine, Plugin {

    private static final Logger LOG = Logger.getLogger(OrchestratorPlugin.class.getName());

    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
    private final FlowParser parser = new FlowParser();
    private TrackerPluginLookup plugins;
    private ExecutorService fallbackExecutor;

    @Activate
    void activate(BundleContext context) {
        plugins = new TrackerPluginLookup(context);
        fallbackExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "atlas-orchestrator");
            thread.setDaemon(true);
            return thread;
        });
        LOG.info(() -> "Atlas plugin orchestrator " + context.getBundle().getVersion() + " activated (in-memory executions)");
    }

    @Deactivate
    void deactivate() {
        plugins.close();
        fallbackExecutor.shutdownNow();
    }

    @Override
    public String define(String definitionJson) {
        Definition definition = parser.parse(definitionJson);
        definitions.put(definition.name(), definition);
        LOG.info(() -> "Flow defined: " + definition.name() + " (" + definition.steps().size() + " steps)");
        return definition.name();
    }

    @Override
    public FlowResult run(String flowName, Map<String, Object> input) {
        Definition definition = definitions.get(flowName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown flow " + flowName);
        }
        return new FlowExecution(definition, input, plugins, JtaTransactionSupport.ofCaller(), executor()).run();
    }

    @Override
    public Set<String> flows() {
        return new TreeSet<>(definitions.keySet());
    }

    /** As a plugin: operation {@code run} with parameters {@code flow} and {@code input}. */
    @Override
    @SuppressWarnings("unchecked")
    public PluginResult execute(PluginRequest request) {
        if (!"run".equals(request.operation())) {
            throw new IllegalArgumentException("orchestrator supports only the 'run' operation");
        }
        Map<String, Object> input = request.get("input") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        FlowResult result = run(request.require("flow"), input);
        if (result.status() != org.atlas.flow.api.FlowStatus.COMPLETED) {
            // The sub-flow already compensated its own steps; failing here makes the parent flow do the same
            throw new org.atlas.plugin.api.PluginException("FLOW_" + result.status(), "Sub-flow " + result.flow() + " "
                + result.status() + " at " + result.failedStep() + ": " + result.message());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", result.status().name());
        data.put("executionId", result.executionId());
        data.put("outputs", result.outputs());
        data.put("failedStep", result.failedStep());
        data.put("message", result.message());
        return new PluginResult(data);
    }

    private ExecutorService executor() {
        try {
            return (ExecutorService) new InitialContext().lookup("java:comp/DefaultManagedExecutorService");
        } catch (NamingException e) {
            return fallbackExecutor;
        }
    }
}
