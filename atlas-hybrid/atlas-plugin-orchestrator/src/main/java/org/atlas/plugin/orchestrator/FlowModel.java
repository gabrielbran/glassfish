package org.atlas.plugin.orchestrator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable model of a flow definition, built by {@link FlowParser} from Atlas metadata.
 */
final class FlowModel {

    private FlowModel() {
    }

    /**
     * @param name unique flow name
     * @param vars constants visible as {@code vars.<name>} (endpoints, data source names...)
     * @param steps executed in order
     */
    record Definition(String name, Map<String, Object> vars, List<Step> steps) {
    }

    sealed interface Step permits ActivityStep, ParallelStep {
        String id();
    }

    /**
     * Exact plugin identity: name plus exact version ({@code rest-client@2.0.0} in metadata).
     */
    record PluginRef(String name, String version) {

        PluginRef {
            Objects.requireNonNull(name, "plugin name");
            Objects.requireNonNull(version, "plugin version");
        }

        @Override
        public String toString() {
            return name + "@" + version;
        }
    }

    /**
     * One plugin invocation, used by steps and compensations.
     */
    record Activity(PluginRef plugin, String operation, Map<String, Object> params) {
    }

    /**
     * @param activity what to invoke
     * @param abortIf business rules evaluated on the result; the first match aborts the flow
     * @param compensation how to undo the activity if the flow aborts or fails later, {@code null} if none
     * @param async fire-and-forget: dispatched after commit, not awaited, result not visible
     */
    record ActivityStep(String id, Activity activity, List<Rule> abortIf, Activity compensation, boolean async)
        implements Step {
    }

    /**
     * Fan-out / fan-in: branches run concurrently and the flow waits for all of them, at most
     * {@code timeoutMillis}.
     */
    record ParallelStep(String id, List<ActivityStep> branches, long timeoutMillis, List<Rule> abortIf) implements Step {
    }

    enum Operator {
        EQUALS, NOT_EQUALS, GREATER_OR_EQUAL;

        boolean test(Object actual, Object expected) {
            return switch (this) {
                case EQUALS -> same(actual, expected);
                case NOT_EQUALS -> !same(actual, expected);
                case GREATER_OR_EQUAL -> actual instanceof Number a && expected instanceof Number e
                    && new BigDecimal(a.toString()).compareTo(new BigDecimal(e.toString())) >= 0;
            };
        }

        private static boolean same(Object actual, Object expected) {
            if (actual instanceof Number a && expected instanceof Number e) {
                return new BigDecimal(a.toString()).compareTo(new BigDecimal(e.toString())) == 0;
            }
            return Objects.equals(actual, expected);
        }
    }

    /**
     * @param path value to inspect, for example {@code steps.scoring.body.resultado}
     */
    record Rule(String path, Operator operator, Object value, String message) {
    }
}
