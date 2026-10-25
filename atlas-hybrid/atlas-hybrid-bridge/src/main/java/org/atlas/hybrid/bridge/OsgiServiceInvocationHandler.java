package org.atlas.hybrid.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Resolves the current service on every call and delegates to it. {@link Object} methods are answered
 * by the proxy itself, so logging or comparing a proxy never requires a service to be present.
 */
final class OsgiServiceInvocationHandler implements InvocationHandler {

    private final OsgiServiceRegistry registry;
    private final ServiceKey key;

    OsgiServiceInvocationHandler(OsgiServiceRegistry registry, ServiceKey key) {
        this.registry = registry;
        this.key = key;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "OsgiServiceProxy[" + key + "]";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
        Object service = registry.service(key);
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
