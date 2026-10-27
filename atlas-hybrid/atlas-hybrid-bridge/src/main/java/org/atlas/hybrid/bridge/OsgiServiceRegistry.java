package org.atlas.hybrid.bridge;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Owns the {@link ServiceTracker}s of one application and hands out dynamic proxies.
 * <p>
 * Trackers are opened lazily on first use and shared by every proxy with the same {@link ServiceKey},
 * so the number of trackers is bounded by the number of distinct injection points, not by the number
 * of bean instances. {@link #close()} must be called when the application stops, otherwise the
 * trackers would keep listening on the framework and leak the application class loader.
 */
final class OsgiServiceRegistry implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(OsgiServiceRegistry.class.getName());
    /** Same names as {@code org.atlas.plugin.api.Plugin}: the bridge does not depend on any contract. */
    static final String ATLAS_PLUGIN_NAME = "atlas.plugin.name";
    static final String ATLAS_PLUGIN_VERSION = "atlas.plugin.version";

    private final Function<Class<?>, BundleContext> contextResolver;
    private final Map<ServiceKey, Tracked> trackers = new ConcurrentHashMap<>();
    private final Map<ServiceKey, Object> proxies = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Production registry: the bundle context is taken from the bundle that loaded the contract.
     */
    OsgiServiceRegistry() {
        this(OsgiServiceRegistry::contextOfContractBundle);
    }

    /**
     * @param contextResolver resolves the bundle context used to track services of a contract type
     */
    OsgiServiceRegistry(Function<Class<?>, BundleContext> contextResolver) {
        this.contextResolver = contextResolver;
    }

    /**
     * @return the shared proxy for the key; creating it never touches the OSGi framework
     */
    Object proxyFor(ServiceKey key) {
        return proxies.computeIfAbsent(key, k -> newProxy(k.type(), k.toString(), () -> service(k)));
    }

    /**
     * Proxies are not cached here: plugin ids may come from user input and must not grow any map.
     *
     * @return a proxy that, on every call, delegates to the best service whose {@value PluginId#PROPERTY}
     *         property equals {@code pluginId}
     */
    Object proxyForPluginId(Class<?> type, String pluginId) {
        String description = PluginId.PROPERTY + "=" + pluginId;
        return newProxy(type, type.getName() + " " + description,
            () -> serviceMatching(type, description, reference -> pluginId.equals(reference.getProperty(PluginId.PROPERTY))));
    }

    /**
     * @return a proxy that, on every call, delegates to the Atlas plugin with this name and exact version
     */
    Object proxyForAtlasPlugin(Class<?> type, String name, String version) {
        String description = "plugin " + name + "@" + version;
        return newProxy(type, type.getName() + " " + description, () -> serviceMatching(type, description,
            reference -> name.equals(reference.getProperty(ATLAS_PLUGIN_NAME)) && version.equals(atlasPluginVersion(reference))));
    }

    /**
     * Uses the contract's unfiltered tracker and compares property values directly: no LDAP filter is
     * built from caller data, and the number of trackers does not depend on the values requested.
     *
     * @return the best service of the contract that matches
     * @throws ServiceUnavailableException if there is none
     */
    Object serviceMatching(Class<?> type, String description, Predicate<ServiceReference<Object>> matches) {
        ServiceKey key = new ServiceKey(type, "", 0);
        Tracked tracked = tracker(key);
        try {
            ServiceTracker<Object, Object> tracker = tracked.tracker();
            ServiceReference<Object>[] references = tracker.getServiceReferences();
            if (references != null) {
                ServiceReference<Object> best = Arrays.stream(references)
                    .filter(matches)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
                Object service = best == null ? null : tracker.getService(best);
                if (service != null) {
                    return service;
                }
            }
            throw new ServiceUnavailableException("No OSGi service available for " + type.getName() + " with " + description);
        } catch (IllegalStateException e) {
            discard(key, tracked);
            throw new ServiceUnavailableException("Contract bundle of " + key + " is no longer active", e);
        }
    }

    /**
     * Atlas plugin version: {@code atlas.plugin.version} when set, otherwise the {@code major.minor.micro}
     * of the providing bundle (same rule as the orchestrator).
     */
    static String atlasPluginVersion(ServiceReference<?> reference) {
        Object explicit = reference.getProperty(ATLAS_PLUGIN_VERSION);
        if (explicit != null) {
            return explicit.toString();
        }
        org.osgi.framework.Version version = reference.getBundle().getVersion();
        return version.getMajor() + "." + version.getMinor() + "." + version.getMicro();
    }

    private static Object newProxy(Class<?> type, String description, Supplier<Object> service) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            new OsgiServiceInvocationHandler(description, service));
    }

    /**
     * @return the best matching service for the key right now
     * @throws ServiceUnavailableException if there is none (after waiting the key's timeout)
     */
    Object service(ServiceKey key) {
        Tracked tracked = tracker(key);
        try {
            ServiceTracker<Object, Object> tracker = tracked.tracker();
            Object service = key.timeout() > 0 ? tracker.waitForService(key.timeout()) : tracker.getService();
            if (service == null) {
                throw new ServiceUnavailableException("No OSGi service available for " + key);
            }
            return service;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while waiting for " + key, e);
        } catch (IllegalStateException e) {
            discard(key, tracked);
            throw new ServiceUnavailableException("Contract bundle of " + key + " is no longer active", e);
        }
    }

    /**
     * @return a snapshot of every service currently matching the key, best first
     */
    List<ServiceDescriptor> describe(ServiceKey key) {
        ServiceReference<Object>[] references = tracker(key).tracker().getServiceReferences();
        if (references == null) {
            return List.of();
        }
        List<ServiceReference<Object>> sorted = new ArrayList<>(Arrays.asList(references));
        // ServiceReference ordering: higher ranking (then lower service.id) is "greater"
        sorted.sort(Comparator.reverseOrder());
        return sorted.stream().map(OsgiServiceRegistry::toDescriptor).toList();
    }

    @Override
    public void close() {
        closed = true;
        trackers.forEach(this::discard);
        proxies.clear();
    }

    /**
     * Returns an open tracker whose bundle context is still valid.
     * <p>
     * When the bundle owning the context stops (contract bundle stopped, updated, uninstalled or
     * refreshed), the framework silently drops its service listeners: the tracker stops receiving
     * events and would keep serving cached, possibly stopped, services forever. Hence the context is
     * validated on every use and the tracker reopened, which fails with a clear message if the contract
     * classes the application is linked against no longer belong to an active bundle.
     */
    private Tracked tracker(ServiceKey key) {
        if (closed) {
            throw new ServiceUnavailableException("Application is stopping, OSGi bridge closed: " + key);
        }
        Tracked tracked = trackers.computeIfAbsent(key, this::openTracker);
        if (tracked.isValid()) {
            return tracked;
        }
        LOG.log(Level.INFO, "Atlas bridge: bundle context for {0} is no longer valid, reopening tracker", key);
        discard(key, tracked);
        return trackers.computeIfAbsent(key, this::openTracker);
    }

    private Tracked openTracker(ServiceKey key) {
        BundleContext context = contextResolver.apply(key.type());
        try {
            ServiceTracker<Object, Object> tracker = new ServiceTracker<>(context, context.createFilter(key.ldapFilter()), null);
            tracker.open();
            LOG.log(Level.FINE, "Tracking OSGi services for {0}", key);
            return new Tracked(context, tracker);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Invalid @OsgiService filter for " + key + ": " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ServiceUnavailableException("Contract bundle of " + key + " is not active", e);
        }
    }

    private void discard(ServiceKey key, Tracked tracked) {
        if (trackers.remove(key, tracked)) {
            try {
                tracked.tracker().close();
            } catch (IllegalStateException e) {
                LOG.log(Level.FINEST, "Tracker for " + key + " already invalid", e);
            }
        }
    }

    private static BundleContext contextOfContractBundle(Class<?> contract) {
        Bundle bundle = FrameworkUtil.getBundle(contract);
        if (bundle == null) {
            throw new ServiceUnavailableException(contract.getName()
                + " was not loaded from an OSGi bundle. Deploy the contract as a bundle"
                + " (asadmin deploy --type=osgi) and do not package it inside the application.");
        }
        BundleContext context = bundle.getBundleContext();
        if (bundle.getState() == Bundle.UNINSTALLED) {
            throw new ServiceUnavailableException("Contract bundle " + bundle.getSymbolicName() + " " + bundle.getVersion()
                + " was uninstalled or replaced. Redeploy the application so it links against the current contract.");
        }
        if (context == null) {
            throw new ServiceUnavailableException("Contract bundle " + bundle.getSymbolicName() + " "
                + bundle.getVersion() + " is not started (state " + bundle.getState() + ")");
        }
        return context;
    }

    private static ServiceDescriptor toDescriptor(ServiceReference<Object> reference) {
        Map<String, Object> properties = new TreeMap<>();
        for (String name : reference.getPropertyKeys()) {
            Object value = reference.getProperty(name);
            properties.put(name, value instanceof Object[] array ? List.of(array) : value);
        }
        Object ranking = reference.getProperty(Constants.SERVICE_RANKING);
        Bundle bundle = reference.getBundle();
        return new ServiceDescriptor(
            (Long) reference.getProperty(Constants.SERVICE_ID),
            ranking instanceof Integer i ? i : 0,
            bundle == null ? null : bundle.getSymbolicName(),
            bundle == null ? null : bundle.getVersion().toString(),
            properties);
    }

    /** A tracker together with the bundle context it listens on. */
    private record Tracked(BundleContext context, ServiceTracker<Object, Object> tracker) {

        boolean isValid() {
            try {
                // Any BundleContext method throws IllegalStateException once the context is invalid
                context.getBundle();
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        }
    }
}
