package org.atlas.hybrid.bridge;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Injects a dynamic proxy to an OSGi service.
 * <p>
 * The proxy resolves the best matching service (highest {@code service.ranking}) on every call, so
 * plugins can be installed, replaced or removed at runtime without redeploying the application.
 * When no service is available, calls fail fast with {@link ServiceUnavailableException}, or wait up
 * to {@link #timeout()} milliseconds for one to appear.
 * <p>
 * The injection point type must be an interface loaded from an OSGi bundle (a contract bundle). Never
 * package contract classes inside the application.
 *
 * <pre>
 * &#64;Inject &#64;OsgiService
 * GreetingService greetings;
 *
 * &#64;Inject &#64;OsgiService(filter = "(atlas.plugin.id=greeting-es)", timeout = 2000)
 * GreetingService spanishOnly;
 * </pre>
 */
@Qualifier
@Documented
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, TYPE })
public @interface OsgiService {

    /**
     * @return additional LDAP filter on service properties, combined with the objectClass of the
     *         injection point type; empty for none
     */
    String filter() default "";

    /**
     * @return milliseconds a call waits for a service to appear before failing; 0 fails immediately
     */
    long timeout() default 0;

    /**
     * Literal for programmatic use, for example with {@code Instance.select(...)}.
     */
    final class Literal extends AnnotationLiteral<OsgiService> implements OsgiService {

        private static final long serialVersionUID = 1L;

        /** Default literal: no filter, no timeout. */
        public static final Literal INSTANCE = new Literal("", 0);

        private final String filter;
        private final long timeout;

        private Literal(String filter, long timeout) {
            this.filter = filter;
            this.timeout = timeout;
        }

        /**
         * @param filter LDAP filter, empty for none
         * @param timeout milliseconds to wait for a service
         * @return a new literal
         */
        public static Literal of(String filter, long timeout) {
            return new Literal(filter, timeout);
        }

        @Override
        public String filter() {
            return filter;
        }

        @Override
        public long timeout() {
            return timeout;
        }
    }
}
