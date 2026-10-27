package org.atlas.flow.api;

/**
 * Final status of a flow execution.
 */
public enum FlowStatus {

    /** Every activity completed; the caller's transaction can commit. */
    COMPLETED,

    /** A business rule ({@code abortIf}) stopped the flow: transaction marked for rollback, compensations run. */
    ABORTED,

    /** An activity failed or timed out: transaction marked for rollback, compensations run. */
    FAILED
}
