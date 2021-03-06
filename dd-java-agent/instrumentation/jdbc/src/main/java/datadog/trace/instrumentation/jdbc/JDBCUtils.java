// Modified by SignalFx
package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.ExceptionLogger;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.regex.Pattern;

public abstract class JDBCUtils {
  private static Field c3poField = null;
  private static final String[] SqlNormalizeClauses = {
    // Numerics
    "0x[0-9a-fA-F]+", // hex
    "(?<![\\p{LC}_0-9])[+.-]*[0-9]+[0-9xEe.+-]*", // regular numeric literal
    // Quoted strings
    "'((?:''|[^'])*)'", // '-quoted
    "\"((?:\"\"|[^\"])*)\"", // "-quoted (some sql flavors support this)
  };
  private static final Pattern SqlNormalizePattern;

  static {
    final String[] clauses = SqlNormalizeClauses;
    // FIXME peformance benchmark and expirment with, e.g., non-capturing grouping
    final String pattern =
        "(" + clauses[0] + ")|(" + clauses[1] + ")|(" + clauses[2] + ")|(" + clauses[3] + ")";
    SqlNormalizePattern = Pattern.compile(pattern);
  }

  /**
   * @param statement
   * @return the unwrapped connection or null if exception was thrown.
   */
  public static Connection connectionFromStatement(final Statement statement) {
    Connection connection;
    try {
      connection = statement.getConnection();

      if (c3poField != null) {
        if (connection.getClass().getName().equals("com.mchange.v2.c3p0.impl.NewProxyConnection")) {
          return (Connection) c3poField.get(connection);
        }
      }

      try {
        // unwrap the connection to cache the underlying actual connection and to not cache proxy
        // objects
        if (connection.isWrapperFor(Connection.class)) {
          connection = connection.unwrap(Connection.class);
        }
      } catch (final Exception | AbstractMethodError e) {
        // Attempt to work around c3po delegating to an connection that doesn't support unwrapping.
        final Class<? extends Connection> connectionClass = connection.getClass();
        if (connectionClass.getName().equals("com.mchange.v2.c3p0.impl.NewProxyConnection")) {
          final Field inner = connectionClass.getDeclaredField("inner");
          inner.setAccessible(true);
          c3poField = inner;
          return (Connection) c3poField.get(connection);
        }

        // perhaps wrapping isn't supported?
        // ex: org.h2.jdbc.JdbcConnection v1.3.175
        // or: jdts.jdbc which always throws `AbstractMethodError` (at least up to version 1.3)
        // Stick with original connection.
      }
    } catch (final Throwable e) {
      // Had some problem getting the connection.
      ExceptionLogger.LOGGER.debug("Could not get connection for StatementAdvice", e);
      return null;
    }
    return connection;
  }

  public static String normalizeSql(String sql) {
    // FIXME performance could be replaced by a much more efficient lexer.
    return SqlNormalizePattern.matcher(sql).replaceAll("?");
  }
}
