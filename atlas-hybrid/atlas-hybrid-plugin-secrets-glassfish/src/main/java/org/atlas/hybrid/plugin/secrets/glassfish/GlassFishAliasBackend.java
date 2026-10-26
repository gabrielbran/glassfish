package org.atlas.hybrid.plugin.secrets.glassfish;

import com.sun.enterprise.security.store.DomainScopedPasswordAliasStore;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.InternalSystemAdministrator;

/**
 * GlassFish implementation, the only place where Atlas touches server internals.
 * <p>
 * Reads go straight to the domain password alias store ({@code domain-passwords.p12}); the HK2 service
 * is per-lookup, so every read sees changes made with {@code asadmin}. Writes run the official
 * {@code create/update/delete-password-alias} admin commands under the internal system identity, as
 * GlassFish's own embedded tooling does, so they get the commands' validation, locking and replication
 * to cluster instances instead of bypassing them.
 */
final class GlassFishAliasBackend implements AliasBackend {

    private ServiceLocator locator() {
        ServiceLocator locator = Globals.getDefaultHabitat();
        if (locator == null) {
            throw new IllegalStateException("GlassFish service locator not available");
        }
        return locator;
    }

    private DomainScopedPasswordAliasStore store() {
        return locator().getService(DomainScopedPasswordAliasStore.class);
    }

    @Override
    public boolean contains(String alias) {
        return store().containsKey(alias);
    }

    @Override
    public char[] get(String alias) {
        DomainScopedPasswordAliasStore store = store();
        return store.containsKey(alias) ? store.get(alias) : null;
    }

    @Override
    public Set<String> aliases() {
        Set<String> aliases = new TreeSet<>();
        for (Iterator<String> keys = store().keys(); keys.hasNext();) {
            aliases.add(keys.next());
        }
        return aliases;
    }

    @Override
    public void create(String alias, char[] password) {
        run("create-password-alias", alias, password);
    }

    @Override
    public void update(String alias, char[] password) {
        run("update-password-alias", alias, password);
    }

    @Override
    public void delete(String alias) {
        run("delete-password-alias", alias, null);
    }

    private void run(String command, String alias, char[] password) {
        ServiceLocator locator = locator();
        CommandRunner<?> runner = locator.getService(CommandRunner.class);
        InternalSystemAdministrator system = locator.getService(InternalSystemAdministrator.class);
        ActionReport report = locator.getService(ActionReport.class, "plain");

        ParameterMap parameters = new ParameterMap();
        parameters.add("DEFAULT", alias);
        if (password != null) {
            // Admin command parameters are Strings: unavoidable copy, short lived
            parameters.add("aliaspassword", new String(password));
        }
        runner.getCommandInvocation(command, report, system.getSubject()).parameters(parameters).execute();

        if (report.getActionExitCode() == ActionReport.ExitCode.FAILURE) {
            throw new IllegalStateException(command + " failed for alias " + alias + ": " + report.getMessage());
        }
    }
}
