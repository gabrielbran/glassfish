package org.atlas.hybrid.plugin.es;

import java.util.logging.Logger;

import org.atlas.hybrid.greeting.api.Greeting;
import org.atlas.hybrid.greeting.api.GreetingService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

/**
 * Plugin implemented as a Declarative Services component: no activator, no OSGi API in the business
 * method, registered and unregistered by SCR when the bundle starts and stops.
 */
@Component(service = GreetingService.class, property = {
    SpanishGreetingService.PLUGIN_ID_PROPERTY + "=" + SpanishGreetingService.PLUGIN_ID,
    Constants.SERVICE_RANKING + ":Integer=10" })
public class SpanishGreetingService implements GreetingService {

    static final String PLUGIN_ID_PROPERTY = "atlas.plugin.id";
    static final String PLUGIN_ID = "greeting-es";

    private static final Logger LOG = Logger.getLogger(SpanishGreetingService.class.getName());

    private String version;

    @Activate
    void activate(BundleContext context) {
        version = context.getBundle().getVersion().toString();
        LOG.info(() -> "Atlas plugin " + PLUGIN_ID + " " + version + " activated");
    }

    @Deactivate
    void deactivate() {
        LOG.info(() -> "Atlas plugin " + PLUGIN_ID + " " + version + " deactivated");
    }

    @Override
    public Greeting greet(String name) {
        return new Greeting("¡Hola, " + name + "!", PLUGIN_ID, version);
    }
}
