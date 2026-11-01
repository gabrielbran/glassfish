package org.atlas.traffic.demo;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

import org.eclipse.microprofile.health.Readiness;

@Path("")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class DemoResource {

    @Inject
    OrdersService orders;

    @Inject
    @Readiness
    DemoReadiness readiness;

    /** Which instance answered (to see the load balancer at work). */
    @GET
    @Path("whoami")
    public Map<String, Object> whoami() {
        return Map.of("instance", System.getProperty("com.sun.aas.instanceName", "?"));
    }

    /** Plain HTTP work in flight. */
    @GET
    @Path("slow")
    public Map<String, Object> slow(@QueryParam("ms") @DefaultValue("1000") long ms) throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("instance", System.getProperty("com.sun.aas.instanceName", "?"), "sleptMs", ms);
    }

    /** HTTP + EJB + JTA transaction + JDBC connection in flight. */
    @GET
    @Path("orders")
    public Map<String, Object> order(@QueryParam("ms") @DefaultValue("1000") long ms) throws Exception {
        return Map.of("instance", System.getProperty("com.sun.aas.instanceName", "?"), "result", orders.process(ms),
            "heldMs", ms);
    }

    /**
     * Same work as {@link #order} but POST: a load balancer must not replay it, so cutting it in flight
     * is a visible failure (and replaying it would execute the business operation twice).
     */
    @POST
    @Path("orders")
    public Map<String, Object> createOrder(@QueryParam("ms") @DefaultValue("1000") long ms) throws Exception {
        return order(ms);
    }

    /** Toggles the application's own readiness check (MicroProfile Health). */
    @POST
    @Path("readiness/{state}")
    public Map<String, Object> readiness(@PathParam("state") String state) {
        readiness.up("up".equals(state));
        return Map.of("instance", System.getProperty("com.sun.aas.instanceName", "?"), "applicationReady", "up".equals(state));
    }
}
