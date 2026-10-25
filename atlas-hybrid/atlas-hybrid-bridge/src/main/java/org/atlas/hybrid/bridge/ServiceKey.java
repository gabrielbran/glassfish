package org.atlas.hybrid.bridge;

import java.util.Objects;

/**
 * Identity of a tracked OSGi service: contract type plus the qualifier members.
 *
 * @param type contract interface
 * @param filter additional LDAP filter, empty for none
 * @param timeout milliseconds to wait for a service, 0 for none
 */
record ServiceKey(Class<?> type, String filter, long timeout) {

    ServiceKey {
        Objects.requireNonNull(type, "type");
        filter = filter == null ? "" : filter.strip();
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must be >= 0: " + timeout);
        }
    }

    static ServiceKey of(Class<?> type, OsgiService qualifier) {
        return new ServiceKey(type, qualifier.filter(), qualifier.timeout());
    }

    OsgiService qualifier() {
        return OsgiService.Literal.of(filter, timeout);
    }

    /**
     * @return the full LDAP filter: the objectClass of the contract, plus the user filter if any
     */
    String ldapFilter() {
        String objectClass = "(objectClass=" + type.getName() + ")";
        return filter.isEmpty() ? objectClass : "(&" + objectClass + filter + ")";
    }

    @Override
    public String toString() {
        return type.getName() + (filter.isEmpty() ? "" : " " + filter) + (timeout == 0 ? "" : " timeout=" + timeout + "ms");
    }
}
