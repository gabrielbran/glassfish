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
import java.util.regex.Pattern;

/**
 * Injects an Atlas plugin by name and <b>exact</b> version:
 *
 * <pre>
 * &#64;Inject &#64;AtlasPlugin(name = "orchestrator", version = "1.0.0")
 * FlowEngine engine;
 *
 * &#64;Inject &#64;Any Instance&lt;Plugin&gt; plugins;
 * Plugin rest = plugins.select(AtlasPlugin.Literal.of("rest-client", "2.0.0")).get();
 * </pre>
 *
 * The plugin name is the {@code atlas.plugin.name} service property; the version is
 * {@code atlas.plugin.version} when set, otherwise the {@code major.minor.micro} of the plugin bundle.
 * <p>
 * Every member is {@link Nonbinding}: CDI resolves one bean per contract at deployment time, and that
 * bean reads name and version from its {@code InjectionPoint}. Binding members would require every
 * combination to exist when the application starts, which would forbid installing plugins later.
 * {@link #description()} is informative only and never used for lookup.
 */
@Qualifier
@Documented
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, TYPE })
public @interface AtlasPlugin {

    @Nonbinding
    String name() default "";

    /** Exact {@code major.minor.micro} version. */
    @Nonbinding
    String version() default "";

    /** Informative: documents why this plugin is used here. */
    @Nonbinding
    String description() default "";

    /**
     * Literal for {@code Instance.select(...)}.
     */
    final class Literal extends AnnotationLiteral<AtlasPlugin> implements AtlasPlugin {

        private static final long serialVersionUID = 1L;
        private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

        /** Qualifier of the synthetic beans; every member is non binding, so it matches any plugin. */
        static final Literal WILDCARD = new Literal("", "", "");

        private final String name;
        private final String version;
        private final String description;

        private Literal(String name, String version, String description) {
            this.name = name;
            this.version = version;
            this.description = description;
        }

        /**
         * @param name plugin name, not blank
         * @param version exact {@code major.minor.micro} version
         * @return a new literal
         */
        public static Literal of(String name, String version) {
            return new Literal(validName(name), validVersion(version), "");
        }

        static String validName(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Plugin name must not be blank");
            }
            return name;
        }

        static String validVersion(String version) {
            if (version == null || !VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("Plugin version must be exact (major.minor.micro), found '" + version + "'");
            }
            return version;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public String description() {
            return description;
        }
    }
}
