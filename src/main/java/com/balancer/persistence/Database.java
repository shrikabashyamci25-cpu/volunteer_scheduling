package com.balancer.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Embedded H2 SQL database. File mode keeps data between runs (./data/balancer.mv.db);
 * memory mode is used by tests. The schema (tables + reporting views) lives in db/schema.sql.
 */
public final class Database {

    private final String url;

    private Database(String url) {
        this.url = url;
    }

    public static Database file(String path) {
        return new Database("jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=-1");
    }

    public static Database inMemory(String name) {
        return new Database("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    public Database init() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("RUNSCRIPT FROM 'classpath:/db/schema.sql'");
            return this;
        } catch (SQLException e) {
            throw new DataAccessException("Schema initialisation failed", e);
        }
    }

    public String url() {
        return url;
    }

    /** Runs work inside one transaction; rolls back on any failure. */
    public <T> T inTransaction(SqlWork<T> work) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Database error: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    public static final class DataAccessException extends RuntimeException {
        public DataAccessException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
