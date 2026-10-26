package org.atlas.hybrid.plugin.secrets.glassfish;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.atlas.hybrid.secrets.api.SecretStore;
import org.atlas.hybrid.secrets.api.SecretStoreException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * {@link SecretStore} on GlassFish password aliases, confined to a namespace.
 * <p>
 * Logical name {@code crm.api-key} maps to alias {@code <prefix>crm.api-key} (default prefix
 * {@value #DEFAULT_PREFIX}). Aliases outside the prefix (admin, keystore, JDBC passwords...) can be
 * neither read, listed, overwritten nor deleted through this service. The prefix can be changed with
 * Config Admin (PID {@value #PID}, property {@value #PREFIX_PROPERTY}) but never be empty.
 * <p>
 * Aliases created here are ordinary GlassFish aliases: they appear in {@code asadmin list-password-aliases}
 * and can be referenced from the domain configuration as {@code ${ALIAS=<prefix><name>}}.
 */
@Component(service = SecretStore.class, configurationPid = GlassFishSecretStore.PID, property = {
    "atlas.plugin.id=secrets-glassfish",
    GlassFishSecretStore.PREFIX_PROPERTY + "=" + GlassFishSecretStore.DEFAULT_PREFIX })
public class GlassFishSecretStore implements SecretStore {

    static final String PID = "org.atlas.hybrid.secrets.glassfish";
    static final String PREFIX_PROPERTY = "atlas.secrets.prefix";
    static final String DEFAULT_PREFIX = "atlas.";

    private static final Logger LOG = Logger.getLogger(GlassFishSecretStore.class.getName());
    private static final Pattern NAME = Pattern.compile(NAME_PATTERN);
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private AliasBackend backend;
    private String prefix;

    /** Used by Declarative Services. */
    public GlassFishSecretStore() {
    }

    GlassFishSecretStore(AliasBackend backend, String prefix) {
        this.backend = backend;
        this.prefix = validPrefix(prefix);
    }

    @Activate
    void activate(Map<String, Object> properties) {
        backend = new GlassFishAliasBackend();
        prefix = validPrefix(String.valueOf(properties.getOrDefault(PREFIX_PROPERTY, DEFAULT_PREFIX)));
        LOG.info(() -> "Atlas plugin secrets-glassfish activated, alias namespace '" + prefix + "'");
    }

    @Override
    public Optional<char[]> read(String name) {
        String alias = alias(name);
        try {
            return Optional.ofNullable(backend.get(alias));
        } catch (RuntimeException e) {
            throw new SecretStoreException("Cannot read secret " + name, e);
        }
    }

    @Override
    public boolean exists(String name) {
        String alias = alias(name);
        try {
            return backend.contains(alias);
        } catch (RuntimeException e) {
            throw new SecretStoreException("Cannot check secret " + name, e);
        }
    }

    @Override
    public void store(String name, char[] secret) {
        String alias = alias(name);
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("Secret " + name + " must not be empty");
        }
        try {
            if (backend.contains(alias)) {
                backend.update(alias, secret);
            } else {
                backend.create(alias, secret);
            }
        } catch (RuntimeException e) {
            throw new SecretStoreException("Cannot store secret " + name, e);
        }
    }

    @Override
    public void delete(String name) {
        String alias = alias(name);
        try {
            if (backend.contains(alias)) {
                backend.delete(alias);
            }
        } catch (RuntimeException e) {
            throw new SecretStoreException("Cannot delete secret " + name, e);
        }
    }

    @Override
    public Set<String> names() {
        try {
            Set<String> names = new TreeSet<>();
            for (String alias : backend.aliases()) {
                if (alias.startsWith(prefix)) {
                    names.add(alias.substring(prefix.length()));
                }
            }
            return names;
        } catch (RuntimeException e) {
            throw new SecretStoreException("Cannot list secrets", e);
        }
    }

    private String alias(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid secret name, expected " + NAME_PATTERN);
        }
        return prefix + name;
    }

    private static String validPrefix(String prefix) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid " + PREFIX_PROPERTY + " '" + prefix
                + "': it must be a non empty namespace such as 'atlas.' or 'crm.'");
        }
        return prefix;
    }
}
