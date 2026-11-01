package org.atlas.traffic;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * Read-only database validations run before an instance accepts load.
 * <p>
 * Configured with system properties (settable per cluster with {@code asadmin create-system-properties}):
 * <pre>
 * atlas.traffic.validation.1.resource = jdbc/clientes
 * atlas.traffic.validation.1.sql      = SELECT 1 FROM DUAL
 * atlas.traffic.validation.2.resource = ...
 * </pre>
 * Read-only is enforced three ways: only statements starting with {@code SELECT}, {@code WITH} or
 * {@code VALUES} are accepted (and no {@code ;}), the connection is put in read-only mode, and the work
 * is always rolled back.
 */
final class ReadOnlyValidation {

    static final String PREFIX = "atlas.traffic.validation.";
    private static final Pattern READ_ONLY = Pattern.compile("^\\s*(SELECT|WITH|VALUES)\\b[^;]*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    record Check(String resource, String sql) {

        Check {
            if (!READ_ONLY.matcher(sql).matches()) {
                throw new IllegalArgumentException("Validation SQL for " + resource
                    + " must be a single SELECT, WITH or VALUES statement: " + sql);
            }
        }
    }

    private final List<Check> checks;
    private final Function<String, DataSource> lookup;

    ReadOnlyValidation(List<Check> checks, Function<String, DataSource> lookup) {
        this.checks = List.copyOf(checks);
        this.lookup = lookup;
    }

    /** Reads {@code atlas.traffic.validation.<n>.resource|sql} for n = 1, 2... until the first gap. */
    static List<Check> fromSystemProperties() {
        List<Check> checks = new ArrayList<>();
        for (int n = 1; System.getProperty(PREFIX + n + ".resource") != null; n++) {
            String sql = System.getProperty(PREFIX + n + ".sql");
            if (sql == null) {
                throw new IllegalArgumentException(PREFIX + n + ".sql is missing");
            }
            checks.add(new Check(System.getProperty(PREFIX + n + ".resource"), sql));
        }
        return checks;
    }

    List<Check> checks() {
        return checks;
    }

    /**
     * @return {@code null} when every check passed, otherwise the first failure
     */
    String run() {
        for (Check check : checks) {
            try (Connection connection = lookup.apply(check.resource()).getConnection()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    try (ResultSet resultSet = statement.executeQuery(check.sql().strip().replaceFirst(";$", ""))) {
                        if (!resultSet.next()) {
                            return check.resource() + ": validation query returned no rows";
                        }
                    }
                } finally {
                    connection.rollback();
                }
            } catch (SQLException | RuntimeException e) {
                return check.resource() + ": " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            }
        }
        return null;
    }
}
