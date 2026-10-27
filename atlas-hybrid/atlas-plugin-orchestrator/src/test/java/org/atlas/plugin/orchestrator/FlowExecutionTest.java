package org.atlas.plugin.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.atlas.flow.api.FlowResult;
import org.atlas.flow.api.FlowStatus;
import org.atlas.plugin.api.PluginException;
import org.atlas.plugin.api.PluginRequest;
import org.atlas.plugin.api.PluginResult;
import org.atlas.plugin.orchestrator.FlowModel.PluginRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FlowExecutionTest {

    /** Records every call as "plugin:operation:param" and answers with the configured function. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, PluginLookup.Resolved> installed = new HashMap<>();
    private final FakeTransaction transaction = new FakeTransaction();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final FlowParser parser = new FlowParser();

    static final class FakeTransaction implements TransactionSupport {
        boolean active = true;
        boolean rollbackOnly;
        Consumer<Outcome> callback;

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void setRollbackOnly() {
            rollbackOnly = true;
        }

        @Override
        public void afterCompletion(Consumer<Outcome> callback) {
            this.callback = callback;
        }
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private void install(String ref, boolean transactional, Function<PluginRequest, Map<String, Object>> behaviour) {
        String name = ref.substring(0, ref.indexOf('@'));
        installed.put(ref, new PluginLookup.Resolved(request -> {
            calls.add(name + ":" + request.operation() + ":" + request.parameters().getOrDefault("tag", ""));
            return new PluginResult(behaviour.apply(request));
        }, transactional));
    }

    private FlowResult run(String json, Map<String, Object> input) {
        PluginLookup lookup = (PluginRef ref) -> installed.get(ref.toString());
        return new FlowExecution(parser.parse(json), input, lookup, transaction, executor).run();
    }

    private static String flow(String steps) {
        return "{\"name\":\"test\",\"vars\":{\"base\":\"http://crm\"},\"steps\":[" + steps + "]}";
    }

    @Test
    void sequentialStepsPassDataThroughExpressions() {
        install("db@1.0.0", true, request -> Map.of("updated", 1L));
        install("rest@1.0.0", false, request -> Map.of("echo", request.parameters()));

        FlowResult result = run(flow("""
            {"id":"save","plugin":"db@1.0.0","operation":"update","params":{"tag":"${input.dni}"}},
            {"id":"call","plugin":"rest@1.0.0","operation":"call",
             "params":{"url":"${vars.base}/clientes/${input.dni}","count":"${steps.save.updated}"}}
            """), Map.of("dni", "123"));

        assertEquals(FlowStatus.COMPLETED, result.status());
        Map<?, ?> echo = (Map<?, ?>) ((Map<?, ?>) result.outputs().get("call")).get("echo");
        assertEquals("http://crm/clientes/123", echo.get("url"));
        assertEquals(1L, echo.get("count"), "a whole-value expression keeps its type");
        assertFalse(transaction.rollbackOnly);
    }

    @Test
    void abortRuleRollsBackAndCompensatesInReverseOrder() {
        install("db@1.0.0", true, request -> Map.of("updated", 1L));
        install("rest@1.0.0", false, request -> Map.of("id", "R-" + request.parameters().getOrDefault("tag", ""),
            "resultado", "RECHAZADO"));

        FlowResult result = run(flow("""
            {"id":"save","plugin":"db@1.0.0","operation":"update"},
            {"id":"crm","plugin":"rest@1.0.0","operation":"call","params":{"tag":"crm"},
             "compensation":{"plugin":"rest@1.0.0","operation":"call","params":{"tag":"undo-${steps.crm.id}"}}},
            {"id":"erp","plugin":"rest@1.0.0","operation":"call","params":{"tag":"erp"},
             "compensation":{"plugin":"rest@1.0.0","operation":"call","params":{"tag":"undo-${steps.erp.id}"}}},
            {"id":"scoring","plugin":"rest@1.0.0","operation":"call","params":{"tag":"scoring"},
             "abortIf":[{"path":"steps.scoring.resultado","equals":"RECHAZADO","message":"Scoring rechazado"}]},
            {"id":"never","plugin":"rest@1.0.0","operation":"call","params":{"tag":"never"}}
            """), Map.of());

        assertEquals(FlowStatus.ABORTED, result.status());
        assertEquals("scoring", result.failedStep());
        assertEquals("Scoring rechazado", result.message());
        assertTrue(transaction.rollbackOnly, "database work is undone by the JTA rollback");
        assertEquals(List.of("erp", "crm"), result.compensated(), "reverse order");
        assertTrue(calls.contains("rest:call:undo-R-erp") && calls.contains("rest:call:undo-R-crm"));
        assertFalse(calls.contains("rest:call:never"));
    }

    @Test
    void pluginErrorFailsTheFlowWithItsCode() {
        install("rest@1.0.0", false, request -> {
            throw new PluginException("TIMEOUT", "too slow");
        });

        FlowResult result = run(flow("{\"id\":\"call\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\"}"), Map.of());

        assertEquals(FlowStatus.FAILED, result.status());
        assertEquals("TIMEOUT: too slow", result.message());
        assertTrue(transaction.rollbackOnly);
    }

    @Test
    void exactVersionIsRequiredAtRuntime() {
        install("rest@1.0.0", false, request -> Map.of());

        FlowResult result = run(flow("{\"id\":\"call\",\"plugin\":\"rest@2.0.0\",\"operation\":\"call\"}"), Map.of());

        assertEquals(FlowStatus.FAILED, result.status());
        assertTrue(result.message().contains("rest@2.0.0 is not installed"));
    }

    @Test
    void transactionalPluginsNeedTheCallerTransactionAndCannotRunInParallel() {
        install("db@1.0.0", true, request -> Map.of());
        install("rest@1.0.0", false, request -> Map.of());

        transaction.active = false;
        FlowResult noTransaction = run(flow("{\"id\":\"save\",\"plugin\":\"db@1.0.0\",\"operation\":\"update\"}"), Map.of());
        assertEquals(FlowStatus.FAILED, noTransaction.status());
        assertTrue(noTransaction.message().contains("inside a JTA transaction"));

        transaction.active = true;
        FlowResult parallel = run(flow("""
            {"id":"both","parallel":[
              {"id":"a","plugin":"rest@1.0.0","operation":"call"},
              {"id":"b","plugin":"db@1.0.0","operation":"update"}]}
            """), Map.of());
        assertEquals(FlowStatus.FAILED, parallel.status());
        assertTrue(parallel.message().contains("cannot run in parallel"));
    }

    @Test
    void parallelBranchesRunConcurrentlyAndTheFlowWaitsForAll() {
        install("rest@1.0.0", false, request -> {
            sleep(300);
            return Map.of("from", request.parameters().get("tag"));
        });

        long start = System.nanoTime();
        FlowResult result = run(flow("""
            {"id":"lookups","timeoutMs":2000,"parallel":[
              {"id":"buro","plugin":"rest@1.0.0","operation":"call","params":{"tag":"buro"}},
              {"id":"crm","plugin":"rest@1.0.0","operation":"call","params":{"tag":"crm"}},
              {"id":"sat","plugin":"rest@1.0.0","operation":"call","params":{"tag":"sat"}}]},
            {"id":"after","plugin":"rest@1.0.0","operation":"call","params":{"tag":"${steps.buro.from}-${steps.sat.from}"}}
            """), Map.of());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(FlowStatus.COMPLETED, result.status());
        assertEquals("buro-sat", ((Map<?, ?>) result.outputs().get("after")).get("from"));
        assertTrue(elapsed < 1000, "3 x 300 ms branches + 300 ms step must take about 600 ms, took " + elapsed);
    }

    @Test
    void parallelTimeoutCompensatesTheBranchesThatCompleted() {
        install("rest@1.0.0", false, request -> {
            if ("slow".equals(request.parameters().get("tag"))) {
                sleep(3000);
            }
            return Map.of("id", "X");
        });

        FlowResult result = run(flow("""
            {"id":"lookups","timeoutMs":300,"parallel":[
              {"id":"fast","plugin":"rest@1.0.0","operation":"call","params":{"tag":"fast"},
               "compensation":{"plugin":"rest@1.0.0","operation":"call","params":{"tag":"undo-fast"}}},
              {"id":"slow","plugin":"rest@1.0.0","operation":"call","params":{"tag":"slow"}}]}
            """), Map.of());

        assertEquals(FlowStatus.FAILED, result.status());
        assertEquals("slow", result.failedStep());
        assertTrue(result.message().contains("timed out"));
        assertEquals(List.of("fast"), result.compensated());
    }

    @Test
    void fireAndForgetWaitsForTheCommitAndIsDiscardedOnRollback() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        install("rest@1.0.0", false, request -> {
            if ("notify".equals(request.parameters().get("tag"))) {
                sent.countDown();
            }
            return Map.of();
        });
        String json = flow("""
            {"id":"notify","plugin":"rest@1.0.0","operation":"call","async":true,"params":{"tag":"notify"}},
            {"id":"crm","plugin":"rest@1.0.0","operation":"call","params":{"tag":"crm"},
             "compensation":{"plugin":"rest@1.0.0","operation":"call","params":{"tag":"undo-crm"}}}
            """);

        FlowResult committed = run(json, Map.of());
        assertEquals(List.of("notify"), committed.dispatched());
        assertFalse(sent.await(200, TimeUnit.MILLISECONDS), "nothing is sent before the commit");
        transaction.callback.accept(TransactionSupport.Outcome.COMMITTED);
        assertTrue(sent.await(2, TimeUnit.SECONDS), "sent after the commit");

        calls.clear();
        run(json, Map.of());
        transaction.callback.accept(TransactionSupport.Outcome.ROLLED_BACK);
        sleep(200);
        assertFalse(calls.contains("rest:call:notify"), "discarded on rollback");
        assertTrue(calls.contains("rest:call:undo-crm"), "a completed flow is compensated if the caller rolls back later");
    }

    @Test
    void withoutTransactionFireAndForgetIsDispatchedImmediately() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        install("rest@1.0.0", false, request -> {
            sent.countDown();
            return Map.of();
        });
        transaction.active = false;

        FlowResult result = run(flow("{\"id\":\"notify\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\",\"async\":true}"),
            Map.of());

        assertEquals(FlowStatus.COMPLETED, result.status());
        assertTrue(sent.await(2, TimeUnit.SECONDS));
    }

    @Test
    void unresolvedExpressionFailsInsteadOfSendingIncompleteData() {
        install("rest@1.0.0", false, request -> Map.of());

        FlowResult result = run(flow("{\"id\":\"call\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\","
            + "\"params\":{\"url\":\"${input.missing}\"}}"), Map.of());

        assertEquals(FlowStatus.FAILED, result.status());
        assertTrue(result.message().contains("${input.missing}"));
    }

    @Test
    void compensationFailuresAreReportedAndDoNotStopTheOthers() {
        install("rest@1.0.0", false, request -> {
            if ("undo-a".equals(request.parameters().get("tag"))) {
                throw new PluginException("HTTP_ERROR", "500");
            }
            return Map.of("ok", "no");
        });

        FlowResult result = run(flow("""
            {"id":"a","plugin":"rest@1.0.0","operation":"call","params":{"tag":"a"},
             "compensation":{"plugin":"rest@1.0.0","operation":"call","params":{"tag":"undo-a"}}},
            {"id":"b","plugin":"rest@1.0.0","operation":"call","params":{"tag":"b"},
             "compensation":{"plugin":"rest@1.0.0","operation":"call","params":{"tag":"undo-b"}}},
            {"id":"c","plugin":"rest@1.0.0","operation":"call","params":{"tag":"c"},
             "abortIf":[{"path":"steps.c.ok","notEquals":"yes"}]}
            """), Map.of());

        assertEquals(List.of("b"), result.compensated());
        assertEquals(List.of("a"), result.compensationFailures());
    }

    @Test
    void invalidDefinitionsAreRejectedUpFront() {
        String[] invalid = {
            flow("{\"id\":\"a\",\"plugin\":\"rest@1.0\",\"operation\":\"call\"}"),
            flow("{\"id\":\"a\",\"plugin\":\"rest\",\"operation\":\"call\"}"),
            flow("{\"id\":\"a\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\"},{\"id\":\"a\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\"}"),
            flow("{\"id\":\"a\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\",\"async\":true,"
                + "\"compensation\":{\"plugin\":\"rest@1.0.0\",\"operation\":\"call\"}}"),
            flow("{\"id\":\"p\",\"parallel\":[{\"id\":\"a\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\"}]}"),
            flow("{\"id\":\"a\",\"plugin\":\"rest@1.0.0\",\"operation\":\"call\",\"abortIf\":[{\"path\":\"x\"}]}"),
        };
        for (String json : invalid) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(json), json);
        }
        assertNotNull(parser.parse(flow("{\"id\":\"a\",\"plugin\":{\"name\":\"rest\",\"version\":\"1.0.0\"},\"operation\":\"call\"}")));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
