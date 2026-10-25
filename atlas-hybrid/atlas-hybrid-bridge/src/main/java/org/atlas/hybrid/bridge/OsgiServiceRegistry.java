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
        return proxies.computeIfAbsent(key, k -> Proxy.newProxyInstance(
            k.type().getClassLoader(), new Class<?>[] { k.type() }, new OsgiServiceInvocationHandler(this, k)));
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
