package org.atlas.plugin.orchestrator;

import java.util.Arrays;
import java.util.Comparator;

import org.atlas.plugin.api.Plugin;
import org.atlas.plugin.orchestrator.FlowModel.PluginRef;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Tracks every {@link Plugin} service. The plugin version is {@value Plugin#VERSION_PROPERTY} when set,
 * otherwise the {@code major.minor.micro} of the providing bundle: installing version 2.0.0 of a
 * plugin next to 1.0.0 makes both selectable, and each flow gets exactly the one it names.
 */
final class TrackerPluginLookup implements PluginLookup, AutoCloseable {

    private final ServiceTracker<Plugin, Plugin> tracker;

    TrackerPluginLookup(BundleContext context) {
        tracker = new ServiceTracker<>(context, Plugin.class, null);
        tracker.open();
    }

    @Override
    public Resolved find(PluginRef ref) {
        ServiceReference<Plugin>[] references = tracker.getServiceReferences();
        if (references == null) {
            return null;
        }
        return Arrays.stream(references)
            .filter(reference -> ref.name().equals(reference.getProperty(Plugin.NAME_PROPERTY)))
            .filter(reference -> ref.version().equals(version(reference)))
            .max(Comparator.naturalOrder())
            .map(reference -> {
                Plugin plugin = tracker.getService(reference);
                return plugin == null ? null
                    : new Resolved(plugin, Boolean.TRUE.equals(reference.getProperty(Plugin.TRANSACTIONAL_PROPERTY)));
            })
            .orElse(null);
    }

    static String version(ServiceReference<?> reference) {
        Object explicit = reference.getProperty(Plugin.VERSION_PROPERTY);
        if (explicit != null) {
            return explicit.toString();
        }
        Version version = reference.getBundle().getVersion();
        return version.getMajor() + "." + version.getMinor() + "." + version.getMicro();
    }

    @Override
    public void close() {
        tracker.close();
    }
}
