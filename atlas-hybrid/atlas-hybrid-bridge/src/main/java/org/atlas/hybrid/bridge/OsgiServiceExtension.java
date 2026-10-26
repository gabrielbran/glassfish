package org.atlas.hybrid.bridge;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.ProcessInjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.FrameworkUtil;

/**
 * CDI portable extension that wires {@link OsgiService} injection points to OSGi services.
 * <p>
 * For every distinct {@code (type, filter, timeout)} found in the application, one synthetic
 * {@link Dependent} bean is registered. Its instances are shared dynamic proxies, so no CDI client
 * proxy is needed and nothing touches the OSGi framework until the first business call. In addition,
 * every contract used through {@code Instance<Contract>} or {@link PluginId} gets one {@link PluginId}
 * bean that serves any plugin id. All trackers are closed when the application shuts down.
 */
public class OsgiServiceExtension implements Extension {

    private static final Logger LOG = Logger.getLogger(OsgiServiceExtension.class.getName());

    private final Set<ServiceKey> keys = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> contracts = ConcurrentHashMap.newKeySet();
    private final OsgiServiceRegistry registry = new OsgiServiceRegistry();

    void collect(@Observes ProcessInjectionPoint<?, ?> event) {
        InjectionPoint injectionPoint = event.getInjectionPoint();
        Type type = injectionPoint.getType();
        OsgiService qualifier = qualifier(injectionPoint, OsgiService.class);
        if (qualifier == null) {
            // Instance<Contract> or @PluginId Contract: needs the @PluginId bean if the contract comes from a bundle.
            // Only the application's own injection points count: in GlassFish every API is a bundle, and the
            // server's bean archives (Soteria, Krazo...) declare Instance<...> injection points of their own.
            if (!declaredByApplication(injectionPoint)) {
                return;
            }
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Instance.class
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> contract && isBundleContract(contract)) {
                contracts.add(contract);
            } else if (type instanceof Class<?> contract && qualifier(injectionPoint, PluginId.class) != null) {
                if (isBundleContract(contract)) {
                    contracts.add(contract);
                } else {
                    event.addDefinitionError(new DefinitionException("@PluginId requires an interface loaded from an"
                        + " OSGi contract bundle, found " + contract.getName() + " at " + injectionPoint.getMember()));
                }
            }
            return;
        }
        if (type instanceof Class<?> contract && contract.isInterface()) {
            keys.add(ServiceKey.of(contract, qualifier));
            contracts.add(contract);
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
        for (Class<?> contract : contracts) {
            event.addBean()
                .beanClass(OsgiServiceExtension.class)
                .types(contract, Object.class)
                .qualifiers(PluginId.Literal.WILDCARD, Any.Literal.INSTANCE)
                .scope(Dependent.class)
                .produceWith(instance -> registry.proxyForPluginId(contract, requestedPluginId(instance, contract)));
            LOG.log(Level.INFO, "Atlas bridge: @PluginId bean registered for {0}", contract.getName());
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

    /**
     * The id is a non binding member, so it is read from the injection point: for {@code Instance.select}
     * CDI reports the qualifiers that were selected.
     */
    private static String requestedPluginId(Instance<Object> instance, Class<?> contract) {
        InjectionPoint injectionPoint = instance.select(InjectionPoint.class).get();
        PluginId pluginId = injectionPoint == null ? null : qualifier(injectionPoint, PluginId.class);
        if (pluginId == null || pluginId.value().isBlank()) {
            throw new IllegalStateException("Select a plugin with Instance<" + contract.getSimpleName()
                + ">.select(PluginId.Literal.of(\"<id>\")) or @PluginId(\"<id>\")");
        }
        return pluginId.value();
    }

    private static <A extends Annotation> A qualifier(InjectionPoint injectionPoint, Class<A> type) {
        return injectionPoint.getQualifiers().stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static boolean declaredByApplication(InjectionPoint injectionPoint) {
        return injectionPoint.getMember() != null
            && FrameworkUtil.getBundle(injectionPoint.getMember().getDeclaringClass()) == null;
    }

    private static boolean isBundleContract(Class<?> type) {
        return type.isInterface() && FrameworkUtil.getBundle(type) != null;
    }
}
