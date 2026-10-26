package org.atlas.hybrid.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Resolves the current service on every call and delegates to it. {@link Object} methods are answered
 * by the proxy itself, so logging or comparing a proxy never requires a service to be present.
 */
final class OsgiServiceInvocationHandler implements InvocationHandler {

    private final String description;
    private final Supplier<Object> service;

    /**
     * @param description shown by {@code toString()}
     * @param service resolves the service to call, or throws {@link ServiceUnavailableException}
     */
    OsgiServiceInvocationHandler(String description, Supplier<Object> service) {
        this.description = description;
        this.service = service;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "OsgiServiceProxy[" + description + "]";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
        Object target = service.get();
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
