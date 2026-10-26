package org.atlas.hybrid.demo;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import org.atlas.hybrid.bridge.OsgiService;
import org.atlas.hybrid.secrets.api.SecretStore;

/**
 * Hybrid EJB: a standard stateless session bean whose secret storage is an OSGi service, backed on
 * GlassFish by password aliases. The bean never logs or returns secret values, and wipes every copy it
 * reads.
 * <p>
 * In a real application protect it with {@code @RolesAllowed}: whoever can call it can manage every
 * secret of the namespace. The demo leaves it open to stay self-contained.
 */
@Stateless
public class CredentialsService {

    @Inject
    @OsgiService
    SecretStore secrets;

    public void store(String name, char[] secret) {
        try {
            secrets.store(name, secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public void delete(String name) {
        secrets.delete(name);
    }

    public Set<String> names() {
        return secrets.names();
    }

    /**
     * Typical use: obtain the secret, use it, wipe it. Here "use" is computing a fingerprint so the
     * demo can prove the value round-trips without ever exposing it.
     */
    public SecretInfo describe(String name) {
        return secrets.read(name).map(secret -> {
            try {
                return new SecretInfo(name, true, secret.length, fingerprint(secret));
            } finally {
                Arrays.fill(secret, '\0');
            }
        }).orElse(new SecretInfo(name, false, 0, null));
    }

    private static String fingerprint(char[] secret) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            if (bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
        }
    }

    /**
     * @param fingerprint first 16 hex digits of the SHA-256 of the secret, {@code null} if absent
     */
    public record SecretInfo(String name, boolean exists, int length, String fingerprint) {
    }
}
