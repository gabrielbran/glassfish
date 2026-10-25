package org.atlas.hybrid.plugin.en;

import java.util.logging.Logger;

import org.atlas.hybrid.greeting.api.Greeting;
import org.atlas.hybrid.greeting.api.GreetingService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

/**
 * Second implementation of the same contract with a higher ranking: while this bundle is active it
 * serves every {@code @OsgiService GreetingService} injection point; when it goes away the Spanish
 * plugin takes over again, without touching the application.
 */
@Component(service = GreetingService.class, property = {
    EnglishGreetingService.PLUGIN_ID_PROPERTY + "=" + EnglishGreetingService.PLUGIN_ID,
    Constants.SERVICE_RANKING + ":Integer=20" })
public class EnglishGreetingService implements GreetingService {

    static final String PLUGIN_ID_PROPERTY = "atlas.plugin.id";
    static final String PLUGIN_ID = "greeting-en";

    private static final Logger LOG = Logger.getLogger(EnglishGreetingService.class.getName());

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
        return new Greeting("Hello, " + name + "!", PLUGIN_ID, version);
    }
}
