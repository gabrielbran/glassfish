package org.atlas.hybrid.demo;

import jakarta.ejb.EJBException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Invalid input (for example a secret name outside the allowed pattern) is the client's fault: 400.
 * System exceptions thrown by an EJB arrive wrapped in {@link EJBException}, so both are handled.
 */
@Provider
public class InvalidRequestMapper implements ExceptionMapper<RuntimeException> {

    @Override
    public Response toResponse(RuntimeException exception) {
        Throwable cause = exception instanceof EJBException && exception.getCause() != null ? exception.getCause() : exception;
        if (cause instanceof IllegalArgumentException) {
            return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "invalid-request", "detail", cause.getMessage())).build();
        }
        if (cause instanceof org.atlas.hybrid.bridge.ServiceUnavailableException unavailable) {
            return new ServiceUnavailableMapper().toResponse(unavailable);
        }
        if (cause instanceof jakarta.ws.rs.WebApplicationException web) {
            return web.getResponse();
        }
        return Response.serverError().type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", "internal-error", "detail", String.valueOf(cause.getMessage()))).build();
    }
}
