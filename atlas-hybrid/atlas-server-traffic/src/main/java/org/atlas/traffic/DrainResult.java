package org.atlas.traffic;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of a drain.
 *
 * @param drained whether everything in flight finished before the timeout
 * @param state state when the drain ended ({@code DRAINED}, or still {@code DRAINING} on timeout)
 * @param duration time spent
 * @param remaining work still in flight when the drain ended
 * @param flushedPools JDBC pools whose idle connections were released
 */
public record DrainResult(boolean drained, TrafficState state, Duration duration, InFlight remaining,
    List<String> flushedPools) {
}
