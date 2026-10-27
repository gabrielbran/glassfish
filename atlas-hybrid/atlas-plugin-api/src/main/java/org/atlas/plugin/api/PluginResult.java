package org.atlas.plugin.api;

import java.util.Map;

/**
 * @param data JSON-like result, never {@code null}; later flow steps read it as {@code steps.<id>.<path>}
 */
public record PluginResult(Map<String, Object> data) {

    public PluginResult {
        data = data == null ? Map.of() : data;
    }
}
