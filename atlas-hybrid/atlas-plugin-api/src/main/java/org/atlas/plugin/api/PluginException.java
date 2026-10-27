package org.atlas.plugin.api;

/**
 * A plugin operation failed. The {@link #code()} is stable and meant for flow rules and monitoring,
 * for example {@code TIMEOUT}, {@code CONNECTION}, {@code SQL}.
 */
public class PluginException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public PluginException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public PluginException(String code, String message) {
        this(code, message, null);
    }

    public String code() {
        return code;
    }
}
