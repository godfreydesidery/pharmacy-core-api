package tz.co.pharmacy.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Counts the database round trips of one request: every statement executed and every commit or
 * rollback on the application's connections, while the request's correlation id (sent as {@code
 * X-Correlation-Id}, read from the MDC the edge sets) starts with {@link #PREFIX}. A round trip is
 * one statement or one commit: the PostgreSQL driver sends BEGIN with the first statement and
 * nothing for auto-commit or read-only switches. The count when the handler starts is kept apart,
 * so the edge's share (authentication, tenant, membership, branch) can be read alone.
 *
 * <p>{@code @Import(StatementCounting.Configuration.class)} wraps the application's data source in
 * the test's own context; requests of other ids are not recorded.
 */
public final class StatementCounting {

  public static final String PREFIX = "count-";

  private static final Map<String, Tally> TALLIES = new ConcurrentHashMap<>();

  private StatementCounting() {}

  /** A new correlation id whose requests are counted. */
  public static String newId() {
    return PREFIX + UUID.randomUUID();
  }

  /** What the requests of {@code correlationId} did (zero when none ran). */
  public static Tally of(String correlationId) {
    return TALLIES.computeIfAbsent(correlationId, id -> new Tally());
  }

  /** One request's round trips. */
  public static final class Tally {
    private final AtomicInteger statements = new AtomicInteger();
    private final AtomicInteger transactions = new AtomicInteger();
    private final AtomicInteger beforeHandler = new AtomicInteger(-1);
    private final AtomicInteger transactionsBeforeHandler = new AtomicInteger(-1);
    private final List<String> sql = new CopyOnWriteArrayList<>();

    /** Statements executed. */
    public int statements() {
      return statements.get();
    }

    /** Transactions ended (commit or rollback). */
    public int transactions() {
      return transactions.get();
    }

    /** Statements plus commits and rollbacks. */
    public int roundTrips() {
      return statements.get() + transactions.get();
    }

    /** Round trips before the handler started (the edge's), or -1 when no handler ran. */
    public int edgeRoundTrips() {
      return beforeHandler.get();
    }

    /** Transactions before the handler started, or -1 when no handler ran. */
    public int edgeTransactions() {
      return transactionsBeforeHandler.get();
    }

    /** The SQL of every statement, in order. */
    public List<String> sql() {
      return List.copyOf(sql);
    }

    /** Round trips after the edge: the handler's own. */
    public int handlerRoundTrips() {
      return roundTrips() - Math.max(0, edgeRoundTrips());
    }

    @Override
    public String toString() {
      return "roundTrips="
          + roundTrips()
          + " statements="
          + statements()
          + " transactions="
          + transactions()
          + " edge="
          + edgeRoundTrips()
          + " (transactions "
          + edgeTransactions()
          + ")";
    }
  }

  private static Tally current() {
    String id = MDC.get("correlationId");
    return id != null && id.startsWith(PREFIX) ? of(id) : null;
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Configuration {

    @Bean
    static BeanPostProcessor countingDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof DataSource dataSource
              && "dataSource".equals(beanName)
              && !(bean instanceof Counting)) {
            return new Counting(dataSource);
          }
          return bean;
        }
      };
    }

    @Bean
    WebMvcConfigurer handlerStart() {
      return new WebMvcConfigurer() {
        @Override
        public void addInterceptors(InterceptorRegistry registry) {
          registry
              .addInterceptor(
                  new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                        HttpServletRequest request, HttpServletResponse response, Object handler) {
                      Tally tally = current();
                      if (tally != null) {
                        tally.beforeHandler.compareAndSet(-1, tally.roundTrips());
                        tally.transactionsBeforeHandler.compareAndSet(-1, tally.transactions());
                      }
                      return true;
                    }
                  })
              .addPathPatterns("/api/**");
        }
      };
    }
  }

  /** The application's data source, counting what its connections do. */
  static final class Counting extends DelegatingDataSource {

    Counting(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return connection(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return connection(super.getConnection(username, password));
    }
  }

  private static final Set<String> EXECUTES =
      Set.of(
          "execute",
          "executeQuery",
          "executeUpdate",
          "executeLargeUpdate",
          "executeBatch",
          "executeLargeBatch");

  private static Connection connection(Connection target) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          String name = method.getName();
          if ("commit".equals(name) || "rollback".equals(name) && (args == null)) {
            Tally tally = current();
            if (tally != null) {
              tally.transactions.incrementAndGet();
            }
          }
          Object result = invoke(target, method, args);
          if (result instanceof Statement statement
              && (name.startsWith("prepare") || "createStatement".equals(name))) {
            String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
            return statement(statement, sql);
          }
          return result;
        };
    return (Connection)
        Proxy.newProxyInstance(
            StatementCounting.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  private static Statement statement(Statement target, String preparedSql) {
    Class<?> type =
        target instanceof CallableStatement
            ? CallableStatement.class
            : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (EXECUTES.contains(method.getName())) {
            Tally tally = current();
            if (tally != null) {
              tally.statements.incrementAndGet();
              String sql =
                  args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
              tally.sql.add(sql == null ? "?" : sql);
            }
          }
          return invoke(target, method, args);
        };
    return (Statement)
        Proxy.newProxyInstance(
            StatementCounting.class.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
