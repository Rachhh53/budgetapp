package app.budget.server.db;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Hands out connections to the one database file and keeps a few open between uses.
 *
 * <p>Opening the file is by far the dearest part of a small read, and a screen that projects
 * balances makes hundreds of them: without this each one opened and closed the file again.
 * A connection given back is kept for the next caller rather than closed.
 *
 * <p>Whoever is about to delete or replace the database file must call {@link #closeIdle()}
 * first: Windows will not let go of a file that is still open.
 */
final class KeptOpenDataSource implements DataSource, AutoCloseable {

    /** Reads rarely overlap in a one-person app; anything beyond this is closed when given back. */
    private static final int MAX_IDLE = 4;

    private final DataSource source;
    private final Deque<Connection> idle = new ArrayDeque<>();
    private boolean closed;

    KeptOpenDataSource(DataSource source) {
        this.source = source;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real;
        synchronized (this) {
            real = idle.pollFirst();
        }
        if (real == null) {
            real = source.getConnection();
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, new Lent(real));
    }

    /** Closes every connection nobody is using. The next caller opens a fresh one. */
    synchronized void closeIdle() {
        for (Connection connection : idle) {
            closeQuietly(connection);
        }
        idle.clear();
    }

    /** For good: connections given back from now on are closed, not kept. */
    @Override
    public synchronized void close() {
        closed = true;
        closeIdle();
    }

    private void giveBack(Connection real) {
        try {
            if (real.isClosed()) {
                return;
            }
            // Whatever the last caller left half done must not become the next caller's.
            if (!real.getAutoCommit()) {
                real.rollback();
                real.setAutoCommit(true);
            }
        } catch (SQLException e) {
            closeQuietly(real);
            return;
        }
        synchronized (this) {
            if (!closed && idle.size() < MAX_IDLE) {
                idle.addFirst(real);
                return;
            }
        }
        closeQuietly(real);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing more can be done with it, and nothing was lost: every change is committed before this.
        }
    }

    /** One connection while a caller has it. Closing it gives the real one back. */
    private final class Lent implements InvocationHandler {
        private final Connection real;
        private boolean returned;

        Lent(Connection real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    if (!returned) {
                        returned = true;
                        giveBack(real);
                    }
                    return null;
                }
                case "isClosed" -> {
                    return returned || real.isClosed();
                }
                default -> {
                    if (returned) {
                        throw new SQLException("This connection has been closed");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // A local file has no accounts.
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return source.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        source.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        source.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return source.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return source.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        return source.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || source.isWrapperFor(type);
    }
}
