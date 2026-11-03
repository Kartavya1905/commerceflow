package dev.commerceflow.systemtests;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only queries against each service's database, straight to Postgres on localhost (not through
 * Toxiproxy, so a cut order-service link does not blind the test). The invariants span databases,
 * so they are checked by joining the answers here.
 */
final class Databases {

  private final String user;
  private final String password;

  Databases(Stack stack) {
    this.user = stack.env.get("POSTGRES_SUPERUSER");
    this.password = stack.env.get("POSTGRES_SUPERUSER_PASSWORD");
  }

  List<Map<String, Object>> query(String database, String sql, Object... params) {
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/" + database, user, password);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      connection.setReadOnly(true);
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery()) {
        int columns = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int c = 1; c <= columns; c++) {
            row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
          }
          rows.add(row);
        }
      }
      return rows;
    } catch (SQLException e) {
      throw new IllegalStateException(database + ": " + e.getMessage(), e);
    }
  }

  long count(String database, String sql, Object... params) {
    return ((Number) query(database, sql, params).getFirst().values().iterator().next())
        .longValue();
  }
}
