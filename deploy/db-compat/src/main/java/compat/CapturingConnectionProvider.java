package compat;

import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.service.spi.Configurable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Captures attempted DDL, including transient index recreation, only in the disposable fixture. */
public final class CapturingConnectionProvider implements ConnectionProvider, Configurable {
    private static final ConcurrentLinkedQueue<String> DDL = new ConcurrentLinkedQueue<>();
    private String url;
    private String username;
    private String password;

    public static void clearCaptured() { DDL.clear(); }
    public static List<String> capturedDdl() { return List.copyOf(DDL); }

    @Override
    public void configure(Map<String, Object> settings) {
        url = String.valueOf(settings.get("hibernate.connection.url"));
        username = String.valueOf(settings.get("hibernate.connection.username"));
        password = String.valueOf(settings.get("hibernate.connection.password"));
        try { Class.forName(String.valueOf(settings.get("hibernate.connection.driver_class"))); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("Fixture JDBC driver missing", failure); }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real = DriverManager.getConnection(url, username, password);
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(real, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (result instanceof Statement statement && (method.getName().equals("createStatement")
                            || method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))) {
                        String prepared = args != null && args.length > 0 && args[0] instanceof String
                                ? (String) args[0] : null;
                        Class<?> contract = method.getReturnType();
                        List<String> batch = new ArrayList<>();
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{contract},
                                (statementProxy, operation, values) -> {
                                    String sql = values != null && values.length > 0 && values[0] instanceof String
                                            ? (String) values[0] : prepared;
                                    if (operation.getName().equals("addBatch") && sql != null) batch.add(sql);
                                    else if (operation.getName().startsWith("execute")) {
                                        if (operation.getName().contains("Batch")) batch.forEach(CapturingConnectionProvider::capture);
                                        else capture(sql);
                                    }
                                    try { return operation.invoke(statement, values); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    }
                    return result;
                });
    }

    private static void capture(String sql) {
        if (sql != null && sql.stripLeading().matches("(?is)^(create|alter|drop|truncate|rename)\\b.*")) DDL.add(sql);
    }

    @Override public void closeConnection(Connection connection) throws SQLException { connection.close(); }
    @Override public boolean supportsAggressiveRelease() { return false; }
    @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
    @Override public <T> T unwrap(Class<T> type) {
        if (!type.isInstance(this)) throw new IllegalArgumentException("Unsupported fixture provider unwrap");
        return type.cast(this);
    }
}
