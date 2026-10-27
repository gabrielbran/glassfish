package org.atlas.plugin.orchestrator;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.function.Consumer;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * JTA through the standard {@code java:comp/TransactionSynchronizationRegistry} (portable Jakarta EE
 * name, no GlassFish internals). It must be created on the caller's thread, where {@code java:comp}
 * belongs to the calling application.
 */
final class JtaTransactionSupport implements TransactionSupport {

    private final TransactionSynchronizationRegistry registry;

    private JtaTransactionSupport(TransactionSynchronizationRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return the caller's transaction support, or one that reports "no transaction" when the caller is
     *         not a Jakarta EE component
     */
    static TransactionSupport ofCaller() {
        try {
            return new JtaTransactionSupport(
                (TransactionSynchronizationRegistry) new InitialContext().lookup("java:comp/TransactionSynchronizationRegistry"));
        } catch (NamingException e) {
            return NONE;
        }
    }

    @Override
    public boolean active() {
        return registry.getTransactionStatus() == Status.STATUS_ACTIVE
            || registry.getTransactionStatus() == Status.STATUS_MARKED_ROLLBACK;
    }

    @Override
    public void setRollbackOnly() {
        registry.setRollbackOnly();
    }

    @Override
    public void afterCompletion(Consumer<Outcome> callback) {
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // nothing to do before the commit
            }

            @Override
            public void afterCompletion(int status) {
                callback.accept(status == Status.STATUS_COMMITTED ? Outcome.COMMITTED : Outcome.ROLLED_BACK);
            }
        });
    }

    static final TransactionSupport NONE = new TransactionSupport() {
        @Override
        public boolean active() {
            return false;
        }

        @Override
        public void setRollbackOnly() {
            throw new IllegalStateException("No transaction");
        }

        @Override
        public void afterCompletion(Consumer<Outcome> callback) {
            throw new IllegalStateException("No transaction");
        }
    };
}
