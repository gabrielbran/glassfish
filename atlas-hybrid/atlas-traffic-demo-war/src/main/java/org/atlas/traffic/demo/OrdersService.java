package org.atlas.traffic.demo;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * EJB whose business method holds a JTA transaction and a JDBC connection for a while: the kind of work
 * a drain must let finish.
 */
@Stateless
public class OrdersService {

    @Resource(lookup = "jdbc/__default")
    DataSource dataSource;

    public long process(long holdMillis) throws SQLException, InterruptedException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("VALUES 1")) {
            resultSet.next();
            Thread.sleep(holdMillis);
            return resultSet.getLong(1);
        }
    }
}
