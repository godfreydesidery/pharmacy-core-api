package tz.co.pharmacy.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Counts the SQL statements one piece of work sends to PostgreSQL, the way a datasource proxy does:
 * every {@code execute*} of a statement is one round trip, and {@code executeBatch} is one too,
 * however many rows it carries. Only connections taken on the thread that {@link #record}s, while
 * it records, are watched; every other connection is the pool's own, untouched. Registered in every
 * test context ({@link IntegrationTest} imports it), so the suite keeps one context.
 */
public class StatementCounter implements BeanPostProcessor {

  private static final ThreadLocal<List<String>> RECORDING = new ThreadLocal<>();

  /** What {@code work} returned and the statements it sent, in order. */
  public record Recorded<T>(T result, List<String> statements) {

    public int count() {
      return statements.size();
    }

    /** The statements whose SQL contains {@code text} (lower case). */
    public List<String> matching(String text) {
      return statements.stream().filter(s -> s.toLowerCase().contains(text)).toList();
    }
  }

  public static <T> Recorded<T> record(Supplier<T> work) {
    List<String> statements = new ArrayList<>();
    List<String> previous = RECORDING.get();
    RECORDING.set(statements);
    try {
      T result = work.get();
      return new Recorded<>(result, List.copyOf(statements));
    } finally {
      if (previous == null) {
        RECORDING.remove();
      } else {
        RECORDING.set(previous);
      }
    }
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof DataSource dataSource && !(bean instanceof Watching)) {
      return new Watching(dataSource);
    }
    return bean;
  }

  /** The application's data source, handing out watched connections while a thread records. */
  static final class Watching extends DelegatingDataSource {

    Watching(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return watched(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return watched(obtainTargetDataSource().getConnection(username, password));
    }
  }

  private static Connection watched(Connection connection) {
    List<String> into = RECORDING.get();
    if (into == null) {
      return connection;
    }
    return (Connection)
        Proxy.newProxyInstance(
            StatementCounter.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = call(connection, method, args);
              if (result instanceof Statement statement) {
                String sql =
                    args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                return watched(statement, method.getReturnType(), sql, into);
              }
              return result;
            });
  }

  private static Object watched(
      Statement statement, Class<?> type, String preparedSql, List<String> into) {
    Class<?> face =
        type == CallableStatement.class
            ? CallableStatement.class
            : type == PreparedStatement.class ? PreparedStatement.class : Statement.class;
    int[] batched = {0};
    InvocationHandler handler =
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.equals("addBatch")) {
            batched[0]++;
          } else if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
            into.add("[batch of " + batched[0] + "] " + preparedSql);
            batched[0] = 0;
          } else if (name.startsWith("execute")) {
            into.add(
                args != null && args.length > 0 && args[0] instanceof String text
                    ? text
                    : preparedSql);
          }
          return call(statement, method, args);
        };
    return Proxy.newProxyInstance(
        StatementCounter.class.getClassLoader(), new Class<?>[] {face}, handler);
  }

  private static Object call(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
