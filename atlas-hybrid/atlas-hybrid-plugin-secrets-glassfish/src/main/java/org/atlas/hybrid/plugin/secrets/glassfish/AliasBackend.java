package org.atlas.hybrid.plugin.secrets.glassfish;

import java.util.Set;

/**
 * Raw access to the server's password aliases, by full alias name. Kept separate from the namespace
 * and validation rules so those can be tested without a running GlassFish.
 */
interface AliasBackend {

    boolean contains(String alias);

    /** @return a copy of the password, or {@code null} if the alias does not exist */
    char[] get(String alias);

    Set<String> aliases();

    void create(String alias, char[] password);

    void update(String alias, char[] password);

    void delete(String alias);
}
