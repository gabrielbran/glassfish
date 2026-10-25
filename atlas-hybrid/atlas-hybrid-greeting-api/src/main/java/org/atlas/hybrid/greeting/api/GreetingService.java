package org.atlas.hybrid.greeting.api;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Example business contract implemented by OSGi plugins and consumed by Jakarta EE applications.
 * <p>
 * Contracts must stay free of OSGi and container types so that business code never depends on the
 * integration technology (see ADR-003).
 */
@ProviderType
public interface GreetingService {

    /**
     * @param name who to greet, never {@code null}
     * @return the greeting produced by the plugin currently serving this contract
     */
    Greeting greet(String name);
}
