package org.atlas.hybrid.demo.flows;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.atlas.flow.api.FlowEngine;
import org.atlas.flow.api.FlowResult;
import org.atlas.hybrid.bridge.AtlasPlugin;

/**
 * Entry point of the flows: a plain stateless EJB. The container owns the JTA transaction (REQUIRED);
 * the orchestrator joins it, marks it for rollback when a flow aborts, and compensates.
 */
@Stateless
public class FlowService {

    private static final Logger LOG = Logger.getLogger(FlowService.class.getName());

    @Inject
    @AtlasPlugin(name = "orchestrator", version = "1.0.0", description = "Motor de flujos en memoria")
    FlowEngine engine;

    @Resource(lookup = "jdbc/__default")
    DataSource dataSource;

    public FlowResult run(String flow, Map<String, Object> input) {
        ensureDefined(flow);
        return engine.run(flow, input);
    }

    /**
     * Runs the flow, then fails on its own: the container rolls back, and the orchestrator compensates
     * the flow even though it had completed.
     */
    public FlowResult runThenFail(String flow, Map<String, Object> input) {
        FlowResult result = run(flow, input);
        LOG.info(() -> "Flow " + flow + " " + result.status() + "; the caller now fails on purpose");
        throw new IllegalStateException("Caller failure after flow " + result.executionId() + " completed");
    }

    /** Flow definitions are Atlas metadata shipped with the application, defined on demand. */
    private void ensureDefined(String flow) {
        if (engine.flows().contains(flow)) {
            return;
        }
        if (!flow.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid flow name " + flow);
        }
        try (InputStream in = FlowService.class.getResourceAsStream("/flows/" + flow + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("Unknown flow " + flow);
            }
            ensureSchema();
            engine.define(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensureSchema() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE ATLAS_CLIENTE (DNI VARCHAR(20) PRIMARY KEY, NOMBRE VARCHAR(100))");
        } catch (SQLException e) {
            if (!"X0Y32".equals(e.getSQLState())) {
                LOG.warning(() -> "Demo schema: " + e.getMessage());
            }
        }
    }
}
