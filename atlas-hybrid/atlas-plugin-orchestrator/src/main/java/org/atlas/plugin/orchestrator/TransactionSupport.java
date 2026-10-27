package org.atlas.plugin.orchestrator;

/**
 * The orchestrator's view of the caller's JTA transaction. It never begins or commits one.
 */
interface TransactionSupport {

    /** Outcome of the caller's transaction, reported after it completes. */
    enum Outcome {
        COMMITTED, ROLLED_BACK
    }

    /**
     * @return whether the calling thread runs inside an active transaction
     */
    boolean active();

    /**
     * Marks the current transaction so that it can only roll back.
     */
    void setRollbackOnly();

    /**
     * Runs {@code callback} once the current transaction completes.
     */
    void afterCompletion(java.util.function.Consumer<Outcome> callback);
}
