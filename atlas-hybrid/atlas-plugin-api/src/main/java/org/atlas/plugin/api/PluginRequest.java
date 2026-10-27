package org.atlas.plugin.api;

import java.util.Map;
import java.util.Objects;

/**
 * @param operation what to do, defined by each plugin (for example {@code call}, {@code query})
 * @param parameters JSON-like parameters, never {@code null}
 */
public record PluginRequest(String operation, Map<String, Object> parameters) {

    public PluginRequest {
        Objects.requireNonNull(operation, "operation");
        parameters = parameters == null ? Map.of() : parameters;
    }

    /**
     * @return the parameter, or {@code null} if absent
     */
    public Object get(String name) {
        return parameters.get(name);
    }

    /**
     * @return the parameter as a string, or {@code defaultValue} if absent
     */
    public String getString(String name, String defaultValue) {
        Object value = parameters.get(name);
        return value == null ? defaultValue : value.toString();
    }

    /**
     * @return the parameter as a string
     * @throws IllegalArgumentException if absent or blank
     */
    public String require(String name) {
        String value = getString(name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing parameter '" + name + "' for operation '" + operation + "'");
        }
        return value;
    }
}
