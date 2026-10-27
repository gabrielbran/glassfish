package org.atlas.plugin.api;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * Common contract of every Atlas plugin: one generic entry point the orchestrator can drive from
 * metadata, whatever the plugin does (REST call, database access, transformation...).
 * <p>
 * Identity is published as OSGi service properties, not in code:
 * <ul>
 * <li>{@value #NAME_PROPERTY}: stable plugin name, for example {@code rest-client};</li>
 * <li>version: the {@code major.minor.micro} of the bundle version, unless {@value #VERSION_PROPERTY}
 * overrides it. Two versions of the same plugin are two bundles installed side by side;</li>
 * <li>{@value #DESCRIPTION_PROPERTY}: human readable description;</li>
 * <li>{@value #TRANSACTIONAL_PROPERTY}: {@code true} if the plugin works inside the caller's JTA
 * transaction (database plugins), which forbids running it in parallel or asynchronously.</li>
 * </ul>
 * Parameters and results are JSON-like values: {@code String}, {@code Number}, {@code Boolean},
 * {@code null}, {@code List} and {@code Map<String, ?>}, so they can come from and go to metadata.
 * Implementations must be thread-safe.
 */
@ConsumerType
public interface Plugin {

    String NAME_PROPERTY = "atlas.plugin.name";
    String VERSION_PROPERTY = "atlas.plugin.version";
    String DESCRIPTION_PROPERTY = "atlas.plugin.description";
    String TRANSACTIONAL_PROPERTY = "atlas.plugin.transactional";

    /**
     * @param request operation and parameters
     * @return the operation result
     * @throws PluginException when the operation fails; the code lets flows react to it
     * @throws IllegalArgumentException when the request is invalid
     */
    PluginResult execute(PluginRequest request);
}
