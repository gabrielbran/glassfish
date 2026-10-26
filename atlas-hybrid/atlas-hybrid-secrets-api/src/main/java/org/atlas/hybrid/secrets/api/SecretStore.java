package org.atlas.hybrid.secrets.api;

import java.util.Optional;
import java.util.Set;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Stores and retrieves application secrets (passwords, API keys, tokens) by logical name.
 * <p>
 * Implementations decide where secrets live (for example GlassFish password aliases) and confine every
 * caller to its own namespace, so an application can never read or overwrite server secrets such as
 * the admin or keystore passwords.
 * <p>
 * Secrets travel as {@code char[]} so callers can wipe them after use ({@code Arrays.fill(secret, '\0')}).
 * Every returned array is a fresh copy owned by the caller.
 */
@ProviderType
public interface SecretStore {

    /**
     * Logical names: 1 to 64 characters among letters, digits, {@code .}, {@code _} and {@code -},
     * starting with a letter or a digit.
     */
    String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    /**
     * @param name logical name, see {@link #NAME_PATTERN}
     * @return a copy of the secret, or empty if it does not exist
     * @throws IllegalArgumentException if the name is invalid
     * @throws SecretStoreException if the store cannot be read
     */
    Optional<char[]> read(String name);

    /**
     * @param name logical name, see {@link #NAME_PATTERN}
     * @return whether the secret exists
     */
    boolean exists(String name);

    /**
     * Creates the secret or replaces its value. The caller keeps ownership of {@code secret}.
     *
     * @param name logical name, see {@link #NAME_PATTERN}
     * @param secret the value, not empty
     * @throws SecretStoreException if the store rejects the change
     */
    void store(String name, char[] secret);

    /**
     * Deletes the secret; does nothing if it does not exist.
     *
     * @param name logical name, see {@link #NAME_PATTERN}
     * @throws SecretStoreException if the store rejects the change
     */
    void delete(String name);

    /**
     * @return the logical names visible to the caller (its namespace only), sorted
     */
    Set<String> names();
}
