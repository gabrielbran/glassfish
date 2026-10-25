package org.atlas.hybrid.bridge;

import java.util.List;

/**
 * Programmatic access to the bridge, injectable with {@code @Inject OsgiServices}.
 * <p>
 * Use it for diagnostics (which plugins are serving a contract) or when the filter is only known at
 * runtime. Business code should prefer {@code @Inject @OsgiService}.
 */
public final class OsgiServices {

    private final OsgiServiceRegistry registry;

    OsgiServices(OsgiServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param contract contract interface loaded from a contract bundle
     * @param filter additional LDAP filter, empty for none
     * @param timeout milliseconds a call waits for a service, 0 for none
     * @return a dynamic proxy, shared with any injection point using the same parameters
     */
    public <T> T proxy(Class<T> contract, String filter, long timeout) {
        requireInterface(contract);
        return contract.cast(registry.proxyFor(new ServiceKey(contract, filter, timeout)));
    }

    /**
     * @param contract contract interface loaded from a contract bundle
     * @return every service currently registered for the contract, best first
     */
    public List<ServiceDescriptor> describe(Class<?> contract) {
        requireInterface(contract);
        return registry.describe(new ServiceKey(contract, "", 0));
    }

    private static void requireInterface(Class<?> contract) {
        if (!contract.isInterface()) {
            throw new IllegalArgumentException("OSGi service contracts must be interfaces: " + contract.getName());
        }
    }
}
