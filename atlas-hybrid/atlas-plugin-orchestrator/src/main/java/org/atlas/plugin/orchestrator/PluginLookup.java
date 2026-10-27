package org.atlas.plugin.orchestrator;

import org.atlas.plugin.api.Plugin;
import org.atlas.plugin.orchestrator.FlowModel.PluginRef;

/**
 * Finds the plugin with an exact name and version among the installed ones.
 */
interface PluginLookup {

    /**
     * @param plugin service object to invoke
     * @param transactional whether it works inside the caller's JTA transaction
     */
    record Resolved(Plugin plugin, boolean transactional) {
    }

    /**
     * @return the plugin, or {@code null} if that exact version is not installed and active
     */
    Resolved find(PluginRef ref);
}
