package org.atlas.hybrid.greeting.api;

/**
 * Immutable result of {@link GreetingService#greet(String)}.
 *
 * @param message the greeting text
 * @param pluginId identifier of the plugin that produced it
 * @param pluginVersion version of the bundle that produced it
 */
public record Greeting(String message, String pluginId, String pluginVersion) {
}
