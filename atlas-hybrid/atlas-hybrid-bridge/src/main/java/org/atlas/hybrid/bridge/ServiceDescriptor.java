package org.atlas.hybrid.bridge;

import java.util.Map;

/**
 * Read-only snapshot of an OSGi service registration, for diagnostics and management endpoints.
 *
 * @param serviceId framework assigned {@code service.id}
 * @param ranking {@code service.ranking}, 0 when absent
 * @param bundle symbolic name of the providing bundle
 * @param bundleVersion version of the providing bundle
 * @param properties all service properties
 */
public record ServiceDescriptor(long serviceId, int ranking, String bundle, String bundleVersion,
    Map<String, Object> properties) {
}
