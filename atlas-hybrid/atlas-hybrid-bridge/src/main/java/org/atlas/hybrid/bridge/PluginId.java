package org.atlas.hybrid.bridge;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Selects the OSGi service whose {@value #PROPERTY} service property equals {@link #value()}, chosen at
 * runtime through {@code Instance}:
 *
 * <pre>
 * &#64;Inject &#64;Any
 * Instance&lt;GreetingService&gt; greetings;
 *
 * GreetingService english = greetings.select(PluginId.Literal.of("greeting-en")).get();
 * </pre>
 *
 * The member is {@link Nonbinding}: a single bean per contract matches every id, and reads the requested
 * id from its {@code InjectionPoint}. Lookups compare the property value directly (no LDAP filter is
 * built from the id) and share one tracker per contract, so ids coming from user input are safe.
 * <p>
 * Contracts are discovered from {@code Instance<Contract>} and {@code @OsgiService Contract} injection
 * points whose type is an interface loaded from an OSGi bundle.
 */
@Qualifier
@Documented
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, TYPE })
public @interface PluginId {

    /** Service property identifying a plugin. */
    String PROPERTY = "atlas.plugin.id";

    /**
     * @return the plugin identifier, value of the {@value #PROPERTY} service property
     */
    @Nonbinding
    String value() default "";

    /**
     * Literal for {@code Instance.select(...)}.
     */
    final class Literal extends AnnotationLiteral<PluginId> implements PluginId {

        private static final long serialVersionUID = 1L;

        /** Qualifier of the synthetic beans; the member is non binding, so it matches every id. */
        static final Literal WILDCARD = new Literal("");

        private final String value;

        private Literal(String value) {
            this.value = value;
        }

        /**
         * @param pluginId plugin identifier, not blank
         * @return a new literal
         */
        public static Literal of(String pluginId) {
            if (pluginId == null || pluginId.isBlank()) {
                throw new IllegalArgumentException("pluginId must not be blank");
            }
            return new Literal(pluginId);
        }

        @Override
        public String value() {
            return value;
        }
    }
}
