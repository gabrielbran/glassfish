package org.atlas.hybrid.demo.flows;

import jakarta.annotation.Resource;
import jakarta.ejb.EJBException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.atlas.hybrid.bridge.OsgiServices;
import org.atlas.hybrid.bridge.ServiceDescriptor;
import org.atlas.plugin.api.Plugin;

/**
 * Demo endpoints: run a flow, list the installed plugins, inspect the database.
 */
@Path("flows")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class FlowResource {

    @Inject
    FlowService flows;

    @Inject
    OsgiServices osgi;

    @Resource(lookup = "jdbc/__default")
    DataSource dataSource;

    /**
     * @param callerFails when true, the EJB fails after the flow completes, to show the compensation of a
     *        completed flow whose transaction rolls back
     */
    @POST
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(@PathParam("name") String name, Map<String, Object> input,
        @QueryParam("callerFails") boolean callerFails) {
        Map<String, Object> withDefaults = new HashMap<>(Map.of("delayMs", 0));
        if (input != null) {
            withDefaults.putAll(input);
        }
        if (!callerFails) {
            return Response.ok(flows.run(name, withDefaults)).build();
        }
        try {
            flows.runThenFail(name, withDefaults);
            return Response.serverError().build();
        } catch (EJBException e) {
            return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("callerFailed", true, "detail", String.valueOf(e.getCause().getMessage()))).build();
        }
    }

    /** Every installed plugin with its name, exact version and description. */
    @GET
    @Path("plugins")
    public List<Map<String, Object>> plugins() {
        List<Map<String, Object>> plugins = new ArrayList<>();
        for (ServiceDescriptor descriptor : osgi.describe(Plugin.class)) {
            Map<String, Object> plugin = new TreeMap<>();
            plugin.put("name", descriptor.properties().get(Plugin.NAME_PROPERTY));
            plugin.put("version", descriptor.bundleVersion().replaceAll("^(\\d+\\.\\d+\\.\\d+).*", "$1"));
            plugin.put("description", descriptor.properties().get(Plugin.DESCRIPTION_PROPERTY));
            plugin.put("bundle", descriptor.bundle());
            plugins.add(plugin);
        }
        return plugins;
    }

    /** Rows of the demo table, read directly (not through the plugins) to verify rollbacks. */
    @GET
    @Path("data/clientes")
    public List<Map<String, Object>> customers() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT DNI, NOMBRE FROM ATLAS_CLIENTE ORDER BY DNI");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(Map.of("dni", resultSet.getString(1), "nombre", resultSet.getString(2)));
            }
        } catch (SQLException e) {
            if (!"42X05".equals(e.getSQLState())) {
                throw e;
            }
        }
        return rows;
    }
}
