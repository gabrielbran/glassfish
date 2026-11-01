package org.atlas.traffic;

/**
 * Traffic lifecycle of one instance. Only {@link #READY} reports readiness UP to the load balancer.
 */
public enum TrafficState {

    /** The server is starting: applications may still be loading. */
    STARTING,

    /** Started: checks and read-only validations must pass several times in a row. */
    VALIDATING,

    /** Validated: the load balancer can send traffic. */
    READY,

    /** Readiness DOWN; in-flight HTTP, JTA and JDBC work is being allowed to finish. */
    DRAINING,

    /** Nothing in flight: safe to stop, patch or redeploy. */
    DRAINED
}
