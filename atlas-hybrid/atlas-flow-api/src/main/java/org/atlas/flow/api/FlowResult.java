package org.atlas.flow.api;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a flow execution.
 *
 * @param flow flow name
 * @param executionId unique id of this execution, also sent as correlation id to the activities
 * @param status final status
 * @param outputs result data of each completed activity, by step id
 * @param compensated ids of the steps whose compensation ran, in execution order
 * @param compensationFailures ids of the steps whose compensation failed (manual follow-up needed)
 * @param dispatched ids of the fire-and-forget steps handed over for asynchronous execution
 * @param failedStep id of the step that aborted or failed the flow, {@code null} if completed
 * @param message reason of the abort or failure, {@code null} if completed
 * @param durationMillis execution time
 */
public record FlowResult(String flow, String executionId, FlowStatus status, Map<String, Object> outputs,
    List<String> compensated, List<String> compensationFailures, List<String> dispatched, String failedStep,
    String message, long durationMillis) {
}
