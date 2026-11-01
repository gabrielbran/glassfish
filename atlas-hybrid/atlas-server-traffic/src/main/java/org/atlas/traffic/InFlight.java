package org.atlas.traffic;

import java.util.Map;
import java.util.TreeMap;

/**
 * Work still running on the instance, the drain waits until it is all zero.
 *
 * @param http HTTP requests being processed by web applications
 * @param transactions active JTA transactions (EJB business methods, timers, MDBs, transactional
 *        servlets), or {@code -1} when transaction monitoring is off and they cannot be counted
 * @param jdbc connections in use, by connection pool (only pools already initialized)
 */
public record InFlight(int http, int transactions, Map<String, Integer> jdbc) {

    public InFlight {
        jdbc = new TreeMap<>(jdbc);
    }

    /** @return whether nothing is running; transactions that cannot be counted do not block the drain */
    public boolean idle() {
        return http == 0 && transactions <= 0 && jdbc.values().stream().allMatch(used -> used == 0);
    }

    public int jdbcInUse() {
        return jdbc.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public String toString() {
        return "http=" + http + ", jta=" + (transactions < 0 ? "n/a (transaction monitoring OFF)" : transactions)
            + ", jdbc=" + jdbcInUse() + (jdbc.isEmpty() ? "" : " " + jdbc);
    }
}
