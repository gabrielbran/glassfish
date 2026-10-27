package org.atlas.plugin.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.atlas.plugin.api.Plugin;
import org.atlas.plugin.api.PluginException;
import org.atlas.plugin.api.PluginRequest;
import org.atlas.plugin.api.PluginResult;
import org.osgi.service.component.annotations.Component;

/**
 * Parameterized SQL on a JDBC resource of the server. Connections come from the server pool, so they
 * join the caller's JTA transaction: a rollback of the flow undoes these changes (ADR-007).
 * <p>
 * Operations:
 * <ul>
 * <li>{@code update}: {@code sql} with {@code ?} placeholders and {@code args} (list); returns {@code updated};</li>
 * <li>{@code query}: same parameters plus {@code maxRows} (default 100); returns {@code rows} (list of maps,
 * upper case column names) and {@code count}.</li>
 * </ul>
 * {@code dataSource} is the JNDI name of the JDBC resource (default {@code jdbc/__default}). Values are
 * always bound as parameters: SQL text never contains data.
 */
@Component(service = Plugin.class, property = {
    Plugin.NAME_PROPERTY + "=database",
    Plugin.DESCRIPTION_PROPERTY + "=SQL parametrizado sobre recursos JDBC del servidor, dentro de la transaccion JTA",
    Plugin.TRANSACTIONAL_PROPERTY + ":Boolean=true" })
public class DatabasePlugin implements Plugin {

    private final Map<String, DataSource> dataSources = new ConcurrentHashMap<>();

    @Override
    public PluginResult execute(PluginRequest request) {
        String sql = request.require("sql");
        List<?> args = request.get("args") instanceof List<?> list ? list : List.of();
        try (Connection connection = dataSource(request.getString("dataSource", "jdbc/__default")).getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                statement.setObject(i + 1, args.get(i));
            }
            return switch (request.operation()) {
                case "update" -> new PluginResult(Map.of("updated", (long) statement.executeUpdate()));
                case "query" -> query(statement, Integer.parseInt(request.getString("maxRows", "100")));
                default -> throw new IllegalArgumentException("database supports 'update' and 'query', not '"
                    + request.operation() + "'");
            };
        } catch (SQLException e) {
            throw new PluginException("SQL", "SQL error " + e.getSQLState() + ": " + e.getMessage(), e);
        }
    }

    private static PluginResult query(PreparedStatement statement, int maxRows) throws SQLException {
        statement.setMaxRows(maxRows);
        List<Object> rows = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    row.put(metaData.getColumnLabel(column).toUpperCase(Locale.ROOT), jsonValue(resultSet.getObject(column)));
                }
                rows.add(row);
            }
        }
        return new PluginResult(Map.of("rows", rows, "count", (long) rows.size()));
    }

    private static Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Long
            || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return value.toString();
    }

    /** Lazy lookup: GlassFish initializes pools on demand, so never in @Activate (findings H-6). */
    private DataSource dataSource(String jndiName) {
        return dataSources.computeIfAbsent(jndiName, name -> {
            try {
                return (DataSource) new InitialContext().lookup(name);
            } catch (NamingException e) {
                throw new PluginException("DATASOURCE", "JDBC resource " + name + " not found", e);
            }
        });
    }
}
