package org.atlas.hybrid.demo.mock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated external REST services used by the orchestrator demo flows. They stand in for the real
 * CRM, credit bureau, blacklist, scoring and notification services.
 */
@Path("mock")
@Produces(MediaType.APPLICATION_JSON)
public class MockServicesResource {

    /** State of the simulated services, inspectable by the demo script. */
    @ApplicationScoped
    public static class MockState {
        final Map<String, Map<String, Object>> crm = new ConcurrentHashMap<>();
        final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();
        final List<Map<String, Object>> notifications = new CopyOnWriteArrayList<>();
        final List<String> crmDeletes = new CopyOnWriteArrayList<>();
        final AtomicInteger sequence = new AtomicInteger();
    }

    @Inject
    MockState state;

    @GET
    @Path("buro/{dni}")
    public Map<String, Object> buro(@PathParam("dni") String dni, @QueryParam("delayMs") @DefaultValue("0") long delayMs)
        throws InterruptedException {
        Thread.sleep(delayMs);
        return Map.of("dni", dni, "score", 720);
    }

    @GET
    @Path("listas-negras/{dni}")
    public Map<String, Object> blacklist(@PathParam("dni") String dni) {
        return Map.of("dni", dni, "encontrado", dni.startsWith("4"));
    }

    @GET
    @Path("scoring/{dni}")
    public Map<String, Object> scoring(@PathParam("dni") String dni) {
        return Map.of("dni", dni, "resultado", dni.endsWith("9") ? "RECHAZADO" : "APROBADO");
    }

    @POST
    @Path("crm/clientes")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createCustomer(Map<String, Object> customer, @HeaderParam("Idempotency-Key") String idempotencyKey) {
        if (idempotencyKey != null && state.idempotencyKeys.containsKey(idempotencyKey)) {
            return Response.ok(state.crm.get(state.idempotencyKeys.get(idempotencyKey))).build();
        }
        String id = "CRM-" + state.sequence.incrementAndGet();
        Map<String, Object> stored = new LinkedHashMap<>(customer);
        stored.put("id", id);
        state.crm.put(id, stored);
        if (idempotencyKey != null) {
            state.idempotencyKeys.put(idempotencyKey, id);
        }
        return Response.status(Response.Status.CREATED).entity(stored).build();
    }

    @DELETE
    @Path("crm/clientes/{id}")
    public Response deleteCustomer(@PathParam("id") String id) {
        state.crmDeletes.add(id);
        return state.crm.remove(id) == null ? Response.status(Response.Status.NOT_FOUND).build() : Response.noContent().build();
    }

    @GET
    @Path("crm/clientes")
    public List<Map<String, Object>> customers() {
        return new ArrayList<>(state.crm.values());
    }

    @POST
    @Path("notificaciones")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response notify(Map<String, Object> notification) {
        state.notifications.add(notification);
        return Response.accepted().build();
    }

    @GET
    @Path("notificaciones")
    public List<Map<String, Object>> notifications() {
        return state.notifications;
    }

    @GET
    @Path("estado")
    public Map<String, Object> summary() {
        return Map.of("crm", new ArrayList<>(state.crm.values()), "crmDeletes", state.crmDeletes,
            "notifications", state.notifications);
    }

    @POST
    @Path("reset")
    public Response reset() {
        state.crm.clear();
        state.idempotencyKeys.clear();
        state.notifications.clear();
        state.crmDeletes.clear();
        return Response.noContent().build();
    }
}
