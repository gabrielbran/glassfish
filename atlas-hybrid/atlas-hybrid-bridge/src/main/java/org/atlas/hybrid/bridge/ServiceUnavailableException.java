package org.atlas.hybrid.bridge;

/**
 * Thrown by an {@link OsgiService} proxy when no matching OSGi service is available. Applications are
 * expected to translate it into a meaningful response (for example HTTP 503) or a fallback.
 */
public class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
