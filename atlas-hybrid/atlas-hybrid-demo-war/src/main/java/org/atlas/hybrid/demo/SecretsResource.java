package org.atlas.hybrid.demo;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Set;

import org.atlas.hybrid.demo.CredentialsService.SecretInfo;

/**
 * Demo endpoints over the hybrid EJB. Values go in (PUT) but never come out: GET returns only
 * existence, length and a fingerprint.
 */
@Path("secrets")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class SecretsResource {

    @Inject
    CredentialsService credentials;

    @GET
    public Set<String> names() {
        return credentials.names();
    }

    @GET
    @Path("{name}")
    public SecretInfo describe(@PathParam("name") String name) {
        return credentials.describe(name);
    }

    @PUT
    @Path("{name}")
    public Response store(@PathParam("name") String name, String value) {
        credentials.store(name, value.strip().toCharArray());
        return Response.noContent().build();
    }

    @DELETE
    @Path("{name}")
    public Response delete(@PathParam("name") String name) {
        credentials.delete(name);
        return Response.noContent().build();
    }
}
