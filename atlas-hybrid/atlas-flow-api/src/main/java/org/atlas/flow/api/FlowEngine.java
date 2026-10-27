package org.atlas.flow.api;

import java.util.Map;
import java.util.Set;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Executes flows: sequences of activities (plugin invocations) described by Atlas metadata.
 * <p>
 * This first engine keeps every execution in memory: an execution lives as long as the call to
 * {@link #run(String, Map)} and does not survive a server restart.
 * <p>
 * Transactions: when {@code run} is called inside a JTA transaction, transactional activities
 * (database plugins) join it. When the flow aborts or fails, the engine marks the transaction for
 * rollback and runs the compensations of the non transactional activities already executed, in
 * reverse order. The caller owns the transaction: the engine never begins or commits one.
 */
@ProviderType
public interface FlowEngine {

    /**
     * Registers a flow, or replaces the one with the same name.
     *
     * @param definitionJson flow definition (Atlas metadata format, see docs/orchestrator.md)
     * @return the flow name
     * @throws IllegalArgumentException if the definition is invalid
     */
    String define(String definitionJson);

    /**
     * Executes a flow synchronously. Fire-and-forget activities are dispatched after the caller's
     * transaction commits (immediately when there is no transaction) and are not awaited.
     *
     * @param flowName a defined flow
     * @param input JSON-like input, visible to the flow as {@code input.<path>}
     * @return the outcome; never throws for activity failures, which are reported in the result
     * @throws IllegalArgumentException if the flow is unknown
     */
    FlowResult run(String flowName, Map<String, Object> input);

    /**
     * @return names of the defined flows
     */
    Set<String> flows();
}
