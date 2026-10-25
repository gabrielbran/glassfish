package org.atlas.hybrid.demo;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

import org.atlas.hybrid.bridge.ServiceUnavailableException;

/**
 * A missing plugin is an expected, temporary condition: answer 503 so clients and load balancers can
 * retry, instead of a generic 500.
 */
@Provider
public class ServiceUnavailableMapper implements ExceptionMapper<ServiceUnavailableException> {

    @Override
    public Response toResponse(ServiceUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .header("Retry-After", "5")
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", "plugin-unavailable", "detail", exception.getMessage()))
            .build();
    }
}
