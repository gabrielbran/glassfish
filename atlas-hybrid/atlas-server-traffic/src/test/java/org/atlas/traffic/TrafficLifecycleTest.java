package org.atlas.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.atlas.traffic.web.TrafficGate;
import org.atlas.traffic.web.TrafficGate.Mode;
import org.junit.jupiter.api.Test;

class TrafficLifecycleTest {

    private final TrafficGate gate = new TrafficGate(java.util.Set.of(4848));
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<String> validation = new AtomicReference<>();
    private final AtomicReference<InFlight> inFlight = new AtomicReference<>(new InFlight(0, 0, Map.of()));
    private final List<String> sleeps = new ArrayList<>();
    private final List<Mode> modesDuringSleep = new ArrayList<>();

    private TrafficLifecycle lifecycle(int requiredSuccesses) {
        return new TrafficLifecycle(gate, started::get, validation::get, inFlight::get, () -> List.of("DerbyPool"),
            millis -> {
                sleeps.add(String.valueOf(millis));
                modesDuringSleep.add(gate.mode());
            }, requiredSuccesses);
    }

    @Test
    void readyOnlyAfterServerStartedAndConsecutiveValidations() {
        TrafficLifecycle lifecycle = lifecycle(3);

        lifecycle.tick();
        assertEquals(TrafficState.STARTING, lifecycle.state(), "applications may still be loading");

        started.set(true);
        lifecycle.tick();
        lifecycle.tick();
        assertEquals(TrafficState.VALIDATING, lifecycle.state());

        validation.set("jdbc/clientes: connection refused");
        lifecycle.tick();
        assertEquals("0/3", lifecycle.status().get("validations"), "a failure resets the count");
        assertEquals("jdbc/clientes: connection refused", lifecycle.status().get("lastFailure"));

        validation.set(null);
        lifecycle.tick();
        lifecycle.tick();
        assertFalse(lifecycle.ready());
        lifecycle.tick();
        assertTrue(lifecycle.ready());
    }

    @Test
    void validatorExceptionsCountAsFailures() {
        TrafficLifecycle lifecycle = new TrafficLifecycle(gate, () -> true, () -> {
            throw new IllegalStateException("boom");
        }, inFlight::get, List::of, millis -> { }, 1);

        lifecycle.tick();

        assertEquals(TrafficState.VALIDATING, lifecycle.state());
        assertEquals("IllegalStateException: boom", lifecycle.status().get("lastFailure"));
    }

    @Test
    void drainServesWithConnectionCloseDuringGraceThenRejectsAndWaitsForWorkInFlight() throws Exception {
        TrafficLifecycle lifecycle = lifecycle(1);
        started.set(true);
        lifecycle.tick();
        assertTrue(lifecycle.ready());

        // Work in flight that finishes after three polls
        AtomicInteger polls = new AtomicInteger();
        TrafficLifecycle draining = new TrafficLifecycle(gate, started::get, validation::get, () -> polls.incrementAndGet() < 4
            ? new InFlight(1, 1, Map.of("OraclePool", 1)) : new InFlight(0, 0, Map.of("OraclePool", 0)),
            () -> List.of("OraclePool"), millis -> modesDuringSleep.add(gate.mode()), 1);
        started.set(true);
        draining.tick();

        DrainResult result = draining.drain(Duration.ofSeconds(5), Duration.ofSeconds(60), true);

        assertTrue(result.drained());
        assertEquals(TrafficState.DRAINED, draining.state());
        assertEquals(Mode.SERVE_AND_CLOSE, modesDuringSleep.get(0), "grace period: still serving, closing connections");
        assertEquals(Mode.REJECT, modesDuringSleep.get(modesDuringSleep.size() - 1), "after grace: new requests rejected");
        assertEquals(List.of("OraclePool"), result.flushedPools());
        assertFalse(draining.ready());
    }

    @Test
    void drainTimeoutReportsWhatIsStillRunning() throws Exception {
        inFlight.set(new InFlight(2, 1, Map.of("OraclePool", 1)));
        TrafficLifecycle lifecycle = new TrafficLifecycle(gate, () -> true, () -> null, inFlight::get, List::of,
            millis -> Thread.sleep(Math.min(millis, 50)), 1);

        DrainResult result = lifecycle.drain(Duration.ZERO, Duration.ofMillis(300), true);

        assertFalse(result.drained());
        assertEquals(TrafficState.DRAINING, result.state(), "stays draining (rejecting) until undrain or stop");
        assertEquals(2, result.remaining().http());
        assertEquals(Mode.REJECT, gate.mode());
    }

    @Test
    void undrainGoesBackToValidationNeverStraightToReady() throws Exception {
        TrafficLifecycle lifecycle = lifecycle(2);
        started.set(true);
        lifecycle.tick();
        lifecycle.tick();
        assertTrue(lifecycle.ready());
        lifecycle.drain(Duration.ZERO, Duration.ofSeconds(1), false);
        assertEquals(TrafficState.DRAINED, lifecycle.state());

        lifecycle.undrain();

        assertEquals(TrafficState.VALIDATING, lifecycle.state());
        assertEquals(Mode.SERVE, gate.mode());
        lifecycle.tick();
        assertFalse(lifecycle.ready());
        lifecycle.tick();
        assertTrue(lifecycle.ready());
    }

    @Test
    void transactionsThatCannotBeCountedDoNotBlockTheDrain() {
        assertTrue(new InFlight(0, -1, Map.of("p", 0)).idle());
        assertFalse(new InFlight(0, 1, Map.of()).idle());
        assertFalse(new InFlight(0, 0, Map.of("p", 2)).idle());
    }

    @Test
    void validationSqlMustBeASingleReadOnlyStatement() {
        for (String sql : List.of("SELECT 1 FROM DUAL", "  select count(*) from clientes where activo = 1 ;", "VALUES 1",
            "WITH x AS (SELECT 1 FROM DUAL) SELECT * FROM x")) {
            new ReadOnlyValidation.Check("jdbc/x", sql);
        }
        for (String sql : List.of("DELETE FROM clientes", "UPDATE clientes SET a = 1", "SELECT 1 FROM DUAL; DROP TABLE x",
            "CALL purge()", "MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN DELETE", "")) {
            assertThrows(IllegalArgumentException.class, () -> new ReadOnlyValidation.Check("jdbc/x", sql), sql);
        }
    }
}
