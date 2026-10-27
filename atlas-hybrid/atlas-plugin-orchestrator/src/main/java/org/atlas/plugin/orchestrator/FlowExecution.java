package org.atlas.plugin.orchestrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.atlas.flow.api.FlowResult;
import org.atlas.flow.api.FlowStatus;
import org.atlas.plugin.api.PluginException;
import org.atlas.plugin.api.PluginRequest;
import org.atlas.plugin.orchestrator.FlowModel.Activity;
import org.atlas.plugin.orchestrator.FlowModel.ActivityStep;
import org.atlas.plugin.orchestrator.FlowModel.Definition;
import org.atlas.plugin.orchestrator.FlowModel.ParallelStep;
import org.atlas.plugin.orchestrator.FlowModel.Rule;
import org.atlas.plugin.orchestrator.FlowModel.Step;
import org.atlas.plugin.orchestrator.PluginLookup.Resolved;
import org.atlas.plugin.orchestrator.TransactionSupport.Outcome;

/**
 * One in-memory execution of a flow. Created and run on the caller's thread; only parallel branches
 * and fire-and-forget activities run on other threads, and they never touch the transaction.
 * <p>
 * Undo strategy when the flow aborts or fails:
 * <ol>
 * <li>transactional activities (database) are undone by marking the caller's JTA transaction for rollback;</li>
 * <li>non transactional activities already executed are undone by their {@code compensation}, in reverse order;</li>
 * <li>fire-and-forget activities waiting for the commit are discarded.</li>
 * </ol>
 * If the flow completes but the caller's transaction rolls back later (for a reason of its own), the
 * compensations run as well, after the rollback.
 */
final class FlowExecution {

    private static final Logger LOG = Logger.getLogger(FlowExecution.class.getName());

    private final Definition flow;
    private final Map<String, Object> input;
    private final PluginLookup plugins;
    private final TransactionSupport transaction;
    private final ExecutorService executor;
    private final String executionId = UUID.randomUUID().toString();

    private final Map<String, Object> outputs = new LinkedHashMap<>();
    private final Deque<Compensable> compensable = new ArrayDeque<>();
    private final List<Prepared> deferred = new ArrayList<>();
    private final List<String> dispatched = new ArrayList<>();
    private boolean inTransaction;

    /** A ready-to-run invocation: plugin resolved, parameters resolved. */
    private record Prepared(String stepId, Resolved plugin, PluginRequest request) {
    }

    private record Compensable(String stepId, Activity compensation) {
    }

    /** Stops the flow: ABORTED by a rule or FAILED by an error. */
    private static final class StepFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FlowStatus status;
        private final String stepId;

        StepFailure(FlowStatus status, String stepId, String message) {
            super(message, null, false, false);
            this.status = status;
            this.stepId = stepId;
        }
    }

    FlowExecution(Definition flow, Map<String, Object> input, PluginLookup plugins, TransactionSupport transaction,
        ExecutorService executor) {
        this.flow = flow;
        this.input = input == null ? Map.of() : input;
        this.plugins = plugins;
        this.transaction = transaction;
        this.executor = executor;
    }

    FlowResult run() {
        long start = System.nanoTime();
        inTransaction = transaction.active();
        try {
            for (Step step : flow.steps()) {
                switch (step) {
                    case ActivityStep activity when activity.async() -> fireAndForget(activity);
                    case ActivityStep activity -> {
                        Prepared prepared = prepare(activity.id(), activity.activity(), false);
                        completed(activity, prepared.plugin(), invoke(prepared));
                        checkRules(activity.id(), activity.abortIf());
                    }
                    case ParallelStep parallel -> parallel(parallel);
                }
            }
        } catch (StepFailure failure) {
            return failed(failure, start);
        }

        if (inTransaction) {
            List<Prepared> afterCommit = List.copyOf(deferred);
            afterCommit.forEach(prepared -> dispatched.add(prepared.stepId()));
            transaction.afterCompletion(outcome -> afterCallerTransaction(outcome, afterCommit));
        }
        return result(FlowStatus.COMPLETED, null, null, List.of(), List.of(), start);
    }

    // ---- activities --------------------------------------------------------------------------

    private Prepared prepare(String stepId, Activity activity, boolean outsideTransaction) {
        Resolved resolved = plugins.find(activity.plugin());
        if (resolved == null) {
            throw new StepFailure(FlowStatus.FAILED, stepId, "Plugin " + activity.plugin() + " is not installed or not active");
        }
        if (resolved.transactional() && outsideTransaction) {
            throw new StepFailure(FlowStatus.FAILED, stepId, "Plugin " + activity.plugin()
                + " is transactional: it cannot run in parallel or asynchronously, outside the flow transaction");
        }
        if (resolved.transactional() && !inTransaction) {
            throw new StepFailure(FlowStatus.FAILED, stepId, "Plugin " + activity.plugin()
                + " is transactional: run the flow inside a JTA transaction");
        }
        try {
            return new Prepared(stepId, resolved,
                new PluginRequest(activity.operation(), Expressions.resolveParams(activity.params(), context())));
        } catch (IllegalArgumentException e) {
            throw new StepFailure(FlowStatus.FAILED, stepId, e.getMessage());
        }
    }

    private Map<String, Object> invoke(Prepared prepared) {
        try {
            return prepared.plugin().plugin().execute(prepared.request()).data();
        } catch (PluginException e) {
            throw new StepFailure(FlowStatus.FAILED, prepared.stepId(), e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            throw new StepFailure(FlowStatus.FAILED, prepared.stepId(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void completed(ActivityStep step, Resolved plugin, Map<String, Object> data) {
        outputs.put(step.id(), data);
        if (step.compensation() != null) {
            if (plugin.transactional()) {
                LOG.warning(() -> "Flow " + flow.name() + ": compensation of transactional step " + step.id()
                    + " ignored, the JTA rollback already undoes it");
            } else {
                compensable.push(new Compensable(step.id(), step.compensation()));
            }
        }
    }

    private void checkRules(String stepId, List<Rule> rules) {
        Map<String, Object> context = context();
        for (Rule rule : rules) {
            if (rule.operator().test(Expressions.lookup(rule.path(), context), rule.value())) {
                throw new StepFailure(FlowStatus.ABORTED, stepId, rule.message());
            }
        }
    }

    // ---- fire and forget ---------------------------------------------------------------------

    private void fireAndForget(ActivityStep step) {
        Prepared prepared = prepare(step.id(), step.activity(), true);
        if (inTransaction) {
            deferred.add(prepared);
        } else {
            submit(prepared);
            dispatched.add(step.id());
        }
    }

    private void submit(Prepared prepared) {
        executor.submit(() -> {
            try {
                prepared.plugin().plugin().execute(prepared.request());
                LOG.fine(() -> "Flow " + flow.name() + " " + executionId + ": async step " + prepared.stepId() + " done");
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Flow " + flow.name() + " " + executionId + ": async step " + prepared.stepId()
                    + " failed (fire-and-forget, not retried by the in-memory engine)", e);
            }
        });
    }

    // ---- parallel ----------------------------------------------------------------------------

    private void parallel(ParallelStep step) {
        List<Prepared> branches = new ArrayList<>();
        for (ActivityStep branch : step.branches()) {
            branches.add(prepare(branch.id(), branch.activity(), true));
        }
        Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
        for (Prepared branch : branches) {
            futures.put(branch.stepId(), executor.submit(() -> branch.plugin().plugin().execute(branch.request()).data()));
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(step.timeoutMillis());
        StepFailure failure = null;
        for (Map.Entry<String, Future<Map<String, Object>>> entry : futures.entrySet()) {
            if (failure != null) {
                entry.getValue().cancel(true);
                continue;
            }
            try {
                entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                failure = new StepFailure(FlowStatus.FAILED, entry.getKey(),
                    "Parallel step " + step.id() + " timed out after " + step.timeoutMillis() + " ms");
                entry.getValue().cancel(true);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                failure = new StepFailure(FlowStatus.FAILED, entry.getKey(), cause instanceof PluginException p
                    ? p.code() + ": " + p.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new StepFailure(FlowStatus.FAILED, step.id(), "Interrupted while waiting for " + step.id());
            }
        }

        // Every branch that did complete is recorded, so it is compensated if the flow does not succeed
        for (int i = 0; i < branches.size(); i++) {
            Future<Map<String, Object>> future = futures.get(branches.get(i).stepId());
            if (future.isDone() && !future.isCancelled()) {
                try {
                    completed(step.branches().get(i), branches.get(i).plugin(), future.get());
                } catch (ExecutionException | CancellationException | InterruptedException e) {
                    // failed branch: nothing to record
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        for (ActivityStep branch : step.branches()) {
            checkRules(branch.id(), branch.abortIf());
        }
        checkRules(step.id(), step.abortIf());
    }

    // ---- end of the flow ---------------------------------------------------------------------

    private FlowResult failed(StepFailure failure, long start) {
        if (inTransaction) {
            transaction.setRollbackOnly();
        }
        List<String> compensated = new ArrayList<>();
        List<String> compensationFailures = new ArrayList<>();
        compensate(compensated, compensationFailures);
        deferred.clear();
        LOG.info(() -> "Flow " + flow.name() + " " + executionId + " " + failure.status + " at " + failure.stepId + ": "
            + failure.getMessage() + (inTransaction ? " (transaction marked for rollback)" : "") + ", compensated "
            + compensated + (compensationFailures.isEmpty() ? "" : ", COMPENSATION FAILURES " + compensationFailures));
        return result(failure.status, failure.stepId, failure.getMessage(), compensated, compensationFailures, start);
    }

    private void afterCallerTransaction(Outcome outcome, List<Prepared> afterCommit) {
        if (outcome == Outcome.COMMITTED) {
            afterCommit.forEach(this::submit);
            return;
        }
        List<String> compensated = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        compensate(compensated, failures);
        LOG.warning(() -> "Flow " + flow.name() + " " + executionId + " completed but the caller's transaction rolled back:"
            + " async steps discarded, compensated " + compensated + (failures.isEmpty() ? "" : ", COMPENSATION FAILURES " + failures));
    }

    /** Reverse order of completion; a failing compensation does not stop the others. */
    private void compensate(List<String> compensated, List<String> failures) {
        while (!compensable.isEmpty()) {
            Compensable item = compensable.pop();
            try {
                Resolved plugin = plugins.find(item.compensation().plugin());
                if (plugin == null || plugin.transactional()) {
                    throw new IllegalStateException(plugin == null ? "plugin " + item.compensation().plugin() + " not available"
                        : "compensations cannot be transactional");
                }
                plugin.plugin().execute(new PluginRequest(item.compensation().operation(),
                    Expressions.resolveParams(item.compensation().params(), context())));
                compensated.add(item.stepId());
            } catch (RuntimeException e) {
                failures.add(item.stepId());
                LOG.log(Level.SEVERE, "Flow " + flow.name() + " " + executionId + ": compensation of " + item.stepId()
                    + " FAILED, manual follow-up required", e);
            }
        }
    }

    private Map<String, Object> context() {
        return Map.of(
            "input", input,
            "vars", flow.vars(),
            "steps", outputs,
            "execution", Map.of("id", executionId, "flow", flow.name()));
    }

    private FlowResult result(FlowStatus status, String failedStep, String message, List<String> compensated,
        List<String> compensationFailures, long start) {
        return new FlowResult(flow.name(), executionId, status, Map.copyOf(outputs), List.copyOf(compensated),
            List.copyOf(compensationFailures), List.copyOf(dispatched), failedStep, message,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
