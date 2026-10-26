package org.atlas.hybrid.plugin.secrets.glassfish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.atlas.hybrid.secrets.api.SecretStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GlassFishSecretStoreTest {

    /** In-memory stand-in for the domain alias store, pre-loaded with server aliases. */
    static class MemoryBackend implements AliasBackend {
        final Map<String, char[]> aliases = new TreeMap<>();

        @Override
        public boolean contains(String alias) {
            return aliases.containsKey(alias);
        }

        @Override
        public char[] get(String alias) {
            char[] value = aliases.get(alias);
            return value == null ? null : value.clone();
        }

        @Override
        public Set<String> aliases() {
            return aliases.keySet();
        }

        @Override
        public void create(String alias, char[] password) {
            if (aliases.putIfAbsent(alias, password.clone()) != null) {
                throw new IllegalStateException("exists");
            }
        }

        @Override
        public void update(String alias, char[] password) {
            aliases.put(alias, password.clone());
        }

        @Override
        public void delete(String alias) {
            aliases.remove(alias);
        }
    }

    private MemoryBackend backend;
    private GlassFishSecretStore store;

    @BeforeEach
    void setUp() {
        backend = new MemoryBackend();
        backend.aliases.put("admin-ssl", "server-secret".toCharArray());
        backend.aliases.put("jdbc-prod", "db-secret".toCharArray());
        store = new GlassFishSecretStore(backend, "atlas.");
    }

    @Test
    void storeCreatesThenUpdatesInsideTheNamespace() {
        store.store("crm.api-key", "v1".toCharArray());
        assertArrayEquals("v1".toCharArray(), backend.aliases.get("atlas.crm.api-key"));

        store.store("crm.api-key", "v2".toCharArray());
        assertArrayEquals("v2".toCharArray(), store.read("crm.api-key").orElseThrow());
        assertEquals(Set.of("crm.api-key"), store.names());
    }

    @Test
    void serverAliasesAreInvisible() {
        assertFalse(store.exists("admin-ssl"));
        assertTrue(store.read("jdbc-prod").isEmpty());
        assertEquals(Set.of(), store.names());

        store.delete("admin-ssl");
        assertTrue(backend.aliases.containsKey("admin-ssl"), "server alias must survive");
    }

    @Test
    void namesCannotEscapeTheNamespace() {
        for (String name : new String[] { "", ".hidden", "../admin-ssl", "a b", "x=y", "${ALIAS=admin-ssl}", "a".repeat(65) }) {
            assertThrows(IllegalArgumentException.class, () -> store.read(name), name);
        }
    }

    @Test
    void readReturnsACopyTheCallerCanWipe() {
        store.store("token", "abc".toCharArray());
        char[] first = store.read("token").orElseThrow();
        Arrays.fill(first, '\0');

        assertArrayEquals("abc".toCharArray(), store.read("token").orElseThrow());
    }

    @Test
    void emptySecretsAndPrefixesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.store("token", new char[0]));
        assertThrows(IllegalArgumentException.class, () -> new GlassFishSecretStore(backend, ""));
    }

    @Test
    void backendFailuresDoNotLeakValues() {
        AliasBackend failing = new MemoryBackend() {
            @Override
            public void create(String alias, char[] password) {
                throw new IllegalStateException("store locked");
            }
        };
        GlassFishSecretStore broken = new GlassFishSecretStore(failing, "atlas.");

        SecretStoreException e = assertThrows(SecretStoreException.class, () -> broken.store("token", "s3cr3t".toCharArray()));
        assertFalse(e.getMessage().contains("s3cr3t"));
    }

    @Test
    void deleteIsIdempotent() {
        store.store("token", "abc".toCharArray());
        store.delete("token");
        store.delete("token");
        assertFalse(store.exists("token"));
    }
}
