package org.atlas.hybrid.demo;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

import org.atlas.hybrid.bridge.OsgiService;
import org.atlas.hybrid.bridge.OsgiServices;
import org.atlas.hybrid.bridge.ServiceDescriptor;
import org.atlas.hybrid.greeting.api.Greeting;
import org.atlas.hybrid.greeting.api.GreetingService;

/**
 * Plain Jakarta REST resource: the only trace of OSGi is the {@link OsgiService} qualifier.
 */
@Path("greetings")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class GreetingResource {

    @Inject
    @OsgiService
    GreetingService greetings;

    @Inject
    @OsgiService(filter = "(atlas.plugin.id=greeting-es)", timeout = 5000)
    GreetingService spanishGreetings;

    @Inject
    OsgiServices osgiServices;

    @GET
    @Path("{name}")
    public Greeting greet(@PathParam("name") String name) {
        return greetings.greet(name);
    }

    @GET
    @Path("es/{name}")
    public Greeting greetInSpanish(@PathParam("name") String name) {
        return spanishGreetings.greet(name);
    }

    @GET
    @Path("plugins")
    public List<ServiceDescriptor> plugins() {
        return osgiServices.describe(GreetingService.class);
    }
}
