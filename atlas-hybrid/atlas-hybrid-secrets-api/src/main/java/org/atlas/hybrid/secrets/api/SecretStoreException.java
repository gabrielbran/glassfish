package org.atlas.hybrid.secrets.api;

/**
 * The secret store could not complete an operation (storage unavailable, change rejected...).
 * Messages never contain secret values.
 */
public class SecretStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
