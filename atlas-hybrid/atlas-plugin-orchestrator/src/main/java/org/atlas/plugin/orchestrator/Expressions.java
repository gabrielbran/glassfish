package org.atlas.plugin.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${path}} placeholders in activity parameters against the execution context
 * ({@code input}, {@code vars}, {@code steps}, {@code execution}).
 * <p>
 * A string that is exactly one placeholder keeps the type of the value (number, map...); placeholders
 * inside a longer string are replaced by their text. An unresolved path is an error, never a silent
 * {@code null}: a typo in metadata must not send an incomplete request.
 */
final class Expressions {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private Expressions() {
    }

    static Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof String text) {
            return resolveString(text, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, item) -> resolved.put(String.valueOf(key), resolve(item, context)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            list.forEach(item -> resolved.add(resolve(item, context)));
            return resolved;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolveParams(Map<String, Object> params, Map<String, Object> context) {
        return (Map<String, Object>) resolve(params, context);
    }

    /**
     * @return the value at a dotted path ({@code steps.crm.body.id}, {@code steps.list.rows.0.NAME}),
     *         {@code null} if any segment is missing
     */
    static Object lookup(String path, Map<String, Object> context) {
        Object current = context;
        for (String segment : path.trim().split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.matches("\\d+") && Integer.parseInt(segment) < list.size()) {
                current = list.get(Integer.parseInt(segment));
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object resolveString(String text, Map<String, Object> context) {
        Matcher whole = PLACEHOLDER.matcher(text);
        if (whole.matches()) {
            return required(whole.group(1), context);
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(required(matcher.group(1), context))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Object required(String path, Map<String, Object> context) {
        Object value = lookup(path, context);
        if (value == null) {
            throw new IllegalArgumentException("Unresolved expression ${" + path + "}");
        }
        return value;
    }
}
