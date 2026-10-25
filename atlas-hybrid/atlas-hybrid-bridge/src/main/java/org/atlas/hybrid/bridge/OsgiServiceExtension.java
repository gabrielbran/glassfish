package org.atlas.hybrid.bridge;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.ProcessInjectionPoint;

import java.lang.reflect.Type;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDI portable extension that wires {@link OsgiService} injection points to OSGi services.
 * <p>
 * For every distinct {@code (type, filter, timeout)} found in the application, one synthetic
 * {@link Dependent} bean is registered. Its instances are shared dynamic proxies, so no CDI client
 * proxy is needed and nothing touches the OSGi framework until the first business call. All trackers
 * are closed when the application shuts down.
 */
public class OsgiServiceExtension implements Extension {

    private static final Logger LOG = Logger.getLogger(OsgiServiceExtension.class.getName());

    private final Set<ServiceKey> keys = ConcurrentHashMap.newKeySet();
    private final OsgiServiceRegistry registry = new OsgiServiceRegistry();

    void collect(@Observes ProcessInjectionPoint<?, ?> event) {
        InjectionPoint injectionPoint = event.getInjectionPoint();
        OsgiService qualifier = injectionPoint.getQualifiers().stream()
            .filter(OsgiService.class::isInstance).map(OsgiService.class::cast).findFirst().orElse(null);
        if (qualifier == null) {
            return;
        }
        Type type = injectionPoint.getType();
        if (type instanceof Class<?> contract && contract.isInterface()) {
            keys.add(ServiceKey.of(contract, qualifier));
        } else {
            event.addDefinitionError(new DefinitionException("@OsgiService requires a non generic interface type, found "
                + type.getTypeName() + " at " + injectionPoint.getMember()));
        }
    }

    void register(@Observes AfterBeanDiscovery event) {
        for (ServiceKey key : keys) {
            event.addBean()
                .beanClass(OsgiServiceExtension.class)
                .types(key.type(), Object.class)
                .qualifiers(key.qualifier(), Any.Literal.INSTANCE)
                .scope(Dependent.class)
                .createWith(context -> registry.proxyFor(key));
            LOG.log(Level.INFO, "Atlas bridge: @OsgiService bean registered for {0}", key);
        }
        event.addBean()
            .beanClass(OsgiServiceExtension.class)
            .types(OsgiServices.class, Object.class)
            .qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE)
            .scope(Dependent.class)
            .createWith(context -> new OsgiServices(registry));
    }

    void shutdown(@Observes BeforeShutdown event) {
        registry.close();
        LOG.log(Level.INFO, "Atlas bridge: OSGi service trackers closed");
    }
}
