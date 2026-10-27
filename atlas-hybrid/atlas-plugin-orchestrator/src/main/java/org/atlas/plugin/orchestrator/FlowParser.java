package org.atlas.plugin.orchestrator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.atlas.plugin.orchestrator.FlowModel.Activity;
import org.atlas.plugin.orchestrator.FlowModel.ActivityStep;
import org.atlas.plugin.orchestrator.FlowModel.Definition;
import org.atlas.plugin.orchestrator.FlowModel.Operator;
import org.atlas.plugin.orchestrator.FlowModel.ParallelStep;
import org.atlas.plugin.orchestrator.FlowModel.PluginRef;
import org.atlas.plugin.orchestrator.FlowModel.Rule;
import org.atlas.plugin.orchestrator.FlowModel.Step;

/**
 * Parses and validates the Atlas flow metadata (JSON). Invalid definitions are rejected when they are
 * defined, never half way through an execution.
 */
final class FlowParser {

    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final long DEFAULT_PARALLEL_TIMEOUT = 30_000;

    private final Json json = new Json();

    Definition parse(String text) {
        Map<String, Object> root = object(json.parse(text), "flow definition");
        String name = id(root.get("name"), "flow name");
        Map<String, Object> vars = root.get("vars") == null ? Map.of() : object(root.get("vars"), "vars");
        List<Object> rawSteps = list(root.get("steps"), "steps");
        if (rawSteps.isEmpty()) {
            throw invalid("flow " + name + " has no steps");
        }
        Set<String> ids = new HashSet<>();
        List<Step> steps = new ArrayList<>();
        for (Object rawStep : rawSteps) {
            Step step = step(object(rawStep, "step"), ids);
            steps.add(step);
        }
        return new Definition(name, vars, List.copyOf(steps));
    }

    private Step step(Map<String, Object> raw, Set<String> ids) {
        String id = id(raw.get("id"), "step id");
        if (!ids.add(id)) {
            throw invalid("duplicate step id " + id);
        }
        if (raw.containsKey("parallel")) {
            List<ActivityStep> branches = new ArrayList<>();
            for (Object rawBranch : list(raw.get("parallel"), "parallel of " + id)) {
                ActivityStep branch = activityStep(object(rawBranch, "branch of " + id), ids);
                if (branch.async()) {
                    throw invalid("branch " + branch.id() + " of " + id + " cannot be async");
                }
                branches.add(branch);
            }
            if (branches.size() < 2) {
                throw invalid("parallel step " + id + " needs at least two branches");
            }
            long timeout = raw.get("timeoutMs") instanceof Number n ? n.longValue() : DEFAULT_PARALLEL_TIMEOUT;
            if (timeout <= 0) {
                throw invalid("timeoutMs of " + id + " must be positive");
            }
            return new ParallelStep(id, List.copyOf(branches), timeout, rules(raw.get("abortIf"), id));
        }
        ids.remove(id);
        return activityStep(raw, ids);
    }

    private ActivityStep activityStep(Map<String, Object> raw, Set<String> ids) {
        String id = id(raw.get("id"), "step id");
        if (!ids.add(id)) {
            throw invalid("duplicate step id " + id);
        }
        boolean async = Boolean.TRUE.equals(raw.get("async"));
        List<Rule> rules = rules(raw.get("abortIf"), id);
        Activity compensation = raw.get("compensation") == null ? null
            : activity(object(raw.get("compensation"), "compensation of " + id), "compensation of " + id);
        if (async && (!rules.isEmpty() || compensation != null)) {
            throw invalid("async step " + id + " cannot have abortIf or compensation: nobody waits for it");
        }
        return new ActivityStep(id, activity(raw, id), rules, compensation, async);
    }

    private Activity activity(Map<String, Object> raw, String where) {
        PluginRef plugin = pluginRef(raw.get("plugin"), where);
        String operation = raw.get("operation") instanceof String s && !s.isBlank() ? s
            : fail("operation is required in " + where);
        Map<String, Object> params = raw.get("params") == null ? Map.of() : object(raw.get("params"), "params of " + where);
        return new Activity(plugin, operation, params);
    }

    /** {@code "name@1.2.0"} or {@code {"name": "...", "version": "..."}}; the version is always exact. */
    private PluginRef pluginRef(Object raw, String where) {
        String name;
        String version;
        if (raw instanceof String text && text.contains("@")) {
            name = text.substring(0, text.indexOf('@'));
            version = text.substring(text.indexOf('@') + 1);
        } else if (raw instanceof Map<?, ?> map) {
            name = String.valueOf(map.get("name"));
            version = String.valueOf(map.get("version"));
        } else {
            throw invalid("plugin of " + where + " must be \"name@major.minor.micro\"");
        }
        if (!VERSION.matcher(version).matches()) {
            throw invalid("plugin version of " + where + " must be exact (major.minor.micro), found '" + version + "'");
        }
        return new PluginRef(id(name, "plugin name of " + where), version);
    }

    private List<Rule> rules(Object raw, String where) {
        if (raw == null) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (Object rawRule : list(raw, "abortIf of " + where)) {
            Map<String, Object> rule = object(rawRule, "rule of " + where);
            String path = rule.get("path") instanceof String s ? s : fail("rule of " + where + " needs a path");
            String message = rule.get("message") instanceof String s ? s : "Rule on " + path + " aborted the flow";
            Rule parsed = null;
            for (Operator operator : Operator.values()) {
                String key = switch (operator) {
                    case EQUALS -> "equals";
                    case NOT_EQUALS -> "notEquals";
                    case GREATER_OR_EQUAL -> "greaterOrEqual";
                };
                if (rule.containsKey(key)) {
                    if (parsed != null) {
                        throw invalid("rule on " + path + " in " + where + " has more than one operator");
                    }
                    parsed = new Rule(path, operator, rule.get(key), message);
                }
            }
            rules.add(parsed != null ? parsed : fail("rule on " + path + " in " + where
                + " needs equals, notEquals or greaterOrEqual"));
        }
        return List.copyOf(rules);
    }

    private static String id(Object raw, String what) {
        if (raw instanceof String s && ID.matcher(s).matches()) {
            return s;
        }
        throw invalid(what + " must match " + ID.pattern() + ", found '" + raw + "'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String what) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw invalid(what + " must be a JSON object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw, String what) {
        if (raw instanceof List<?> items) {
            return (List<Object>) items;
        }
        throw invalid(what + " must be a JSON array");
    }

    private static <T> T fail(String message) {
        throw invalid(message);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid flow definition: " + message);
    }
}
