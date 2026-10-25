package org.atlas.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.felix.framework.Felix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

/**
 * Exercises the dynamic behaviour of the bridge against a real (embedded) Felix framework, the same
 * framework implementation GlassFish runs on.
 */
class OsgiServiceRegistryTest {

    /** Stand-in for a contract interface. */
    public interface Greeter {
        String greet(String name);
    }

    private Felix felix;
    private BundleContext context;
    private OsgiServiceRegistry registry;

    @BeforeEach
    void startFramework() throws Exception {
        Path storage = Files.createTempDirectory("felix-test");
        felix = new Felix(Map.of(
            Constants.FRAMEWORK_STORAGE, storage.toString(),
            Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT));
        felix.start();
        context = felix.getBundleContext();
        registry = new OsgiServiceRegistry(type -> context);
    }

    @AfterEach
    void stopFramework() throws Exception {
        registry.close();
        felix.stop();
        felix.waitForStop(10_000);
    }

    @Test
    void proxyFollowsRankingAndSurvivesPluginChurn() {
        Greeter greeter = (Greeter) registry.proxyFor(new ServiceKey(Greeter.class, "", 0));

        assertThrows(ServiceUnavailableException.class, () -> greeter.greet("Ana"), "no plugin yet");

        ServiceRegistration<?> spanish = register(name -> "Hola " + name, 10, "es");
        assertEquals("Hola Ana", greeter.greet("Ana"));

        ServiceRegistration<?> english = register(name -> "Hello " + name, 20, "en");
        assertEquals("Hello Ana", greeter.greet("Ana"), "higher ranking wins");

        english.unregister();
        assertEquals("Hola Ana", greeter.greet("Ana"), "falls back to the remaining plugin");

        spanish.unregister();
        assertThrows(ServiceUnavailableException.class, () -> greeter.greet("Ana"));
    }

    @Test
    void filterSelectsPlugin() {
        register(name -> "Hola " + name, 10, "es");
        register(name -> "Hello " + name, 20, "en");

        Greeter spanishOnly = (Greeter) registry.proxyFor(new ServiceKey(Greeter.class, "(atlas.plugin.id=es)", 0));

        assertEquals("Hola Ana", spanishOnly.greet("Ana"));
    }

    @Test
    void timeoutWaitsForLatePlugin() throws Exception {
        Greeter greeter = (Greeter) registry.proxyFor(new ServiceKey(Greeter.class, "", 5_000));
        Thread late = new Thread(() -> {
            sleep(300);
            register(name -> "Hola " + name, 0, "late");
        });
        late.start();

        assertEquals("Hola Ana", greeter.greet("Ana"));
        late.join();
    }

    @Test
    void sameKeySharesProxyAndObjectMethodsNeedNoService() {
        Object first = registry.proxyFor(new ServiceKey(Greeter.class, "", 0));
        Object second = registry.proxyFor(new ServiceKey(Greeter.class, " ", 0));

        assertSame(first, second);
        assertTrue(first.toString().contains(Greeter.class.getName()));
        assertEquals(first, second);
    }

    @Test
    void exceptionsFromPluginArePropagatedUnwrapped() {
        register(name -> {
            throw new IllegalArgumentException("bad name");
        }, 0, "broken");
        Greeter greeter = (Greeter) registry.proxyFor(new ServiceKey(Greeter.class, "", 0));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> greeter.greet("x"));
        assertEquals("bad name", e.getMessage());
    }

    @Test
    void describeListsBestFirst() {
        register(name -> "Hola " + name, 10, "es");
        register(name -> "Hello " + name, 20, "en");

        List<ServiceDescriptor> services = registry.describe(new ServiceKey(Greeter.class, "", 0));

        assertEquals(2, services.size());
        assertEquals("en", services.get(0).properties().get("atlas.plugin.id"));
        assertEquals(20, services.get(0).ranking());
    }

    @Test
    void closedRegistryRejectsCalls() {
        register(name -> "Hola " + name, 0, "es");
        Greeter greeter = (Greeter) registry.proxyFor(new ServiceKey(Greeter.class, "", 0));
        assertEquals("Hola Ana", greeter.greet("Ana"));

        registry.close();

        assertThrows(ServiceUnavailableException.class, () -> greeter.greet("Ana"));
    }

    @Test
    void invalidFilterIsReported() {
        Greeter greeter = (Greeter) registry.proxyFor(new ServiceKey(Greeter.class, "(broken", 0));

        assertThrows(IllegalArgumentException.class, () -> greeter.greet("Ana"));
    }

    @Test
    void stoppedContractBundleNeverServesStaleServices() throws Exception {
        // Simulates the contract bundle: the tracker listens on its context, as in GlassFish
        Bundle contract = installEmptyBundle("contract");
        contract.start();
        OsgiServiceRegistry contractRegistry = new OsgiServiceRegistry(type -> {
            BundleContext ctx = contract.getBundleContext();
            if (ctx == null) {
                throw new ServiceUnavailableException("contract not started");
            }
            return ctx;
        });
        try {
            Greeter greeter = (Greeter) contractRegistry.proxyFor(new ServiceKey(Greeter.class, "", 0));
            ServiceRegistration<?> spanish = register(name -> "Hola " + name, 0, "es");
            assertEquals("Hola Ana", greeter.greet("Ana"));

            // Stopping the bundle drops its listeners: the tracker would never see the unregistration
            contract.stop();
            spanish.unregister();
            assertThrows(ServiceUnavailableException.class, () -> greeter.greet("Ana"), "must not serve a stale service");

            // Contract back: the bridge recovers on its own
            contract.start();
            register(name -> "Hello " + name, 0, "en");
            assertEquals("Hello Ana", greeter.greet("Ana"));
        } finally {
            contractRegistry.close();
        }
    }

    private Bundle installEmptyBundle(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        Path jar = Files.createTempFile(symbolicName, ".jar");
        try (OutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // manifest only
        }
        return context.installBundle(jar.toUri().toString());
    }

    private ServiceRegistration<?> register(Greeter greeter, int ranking, String id) {
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, ranking);
        properties.put("atlas.plugin.id", id);
        return context.registerService(Greeter.class.getName(), greeter, properties);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
