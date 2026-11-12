package com.odimall.auth.credential;

import com.odimall.auth.model.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The single named component that reads the `users` table.
 *
 * <p><b>Odigos custom-instrumentation target.</b> Instrument
 * {@link #findByUsername(String)}: the ARGUMENT is the attacker's raw input (a
 * tautology like {@code ' OR '1'='1}), and the RETURN VALUE ({@link CredentialLookup})
 * shows the executed SQL and the row count. A normal login argument is a plain
 * username matching one row; an injection argument matches the whole table.
 */
@Component
public class CredentialStore {

    private static final Logger logger = LoggerFactory.getLogger(CredentialStore.class);
    private static final String COLUMNS = "id, username, email, role, password_hash";

    private final DataSource dataSource;

    @Value("${auth.secure-queries:false}")
    private boolean secureQueries;

    public CredentialStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Look up a user by username. When secure-queries is false (the demo default)
     * the username is concatenated straight into the SQL string — a payload such as
     * {@code ' OR '1'='1} makes the WHERE clause a tautology and dumps every row.
     */
    public CredentialLookup findByUsername(String username) {
        if (secureQueries) {
            return findByUsernameSafe(username);
        }
        // VULNERABLE: raw string concatenation. This is the point of the demo.
        String sql = "SELECT " + COLUMNS + " FROM users WHERE username = '" + username + "' LIMIT 100";
        List<UserRecord> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
        } catch (Exception e) {
            logger.error("findByUsername failed: {}", e.getMessage());
            throw new RuntimeException("credential lookup failed", e);
        }
        return new CredentialLookup(rows, sql);
    }

    /** Parameterized lookup — the remediated path (AUTH_SECURE_QUERIES=true). */
    public CredentialLookup findByUsernameSafe(String username) {
        String sql = "SELECT " + COLUMNS + " FROM users WHERE username = ? LIMIT 1";
        List<UserRecord> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        } catch (Exception e) {
            logger.error("findByUsernameSafe failed: {}", e.getMessage());
            throw new RuntimeException("credential lookup failed", e);
        }
        return new CredentialLookup(rows, sql);
    }

    /** Count of credential rows — used by the health check. */
    public long countUsers() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM users")) {
            return rs.next() ? rs.getLong("n") : 0L;
        } catch (Exception e) {
            throw new RuntimeException("count users failed", e);
        }
    }

    public boolean isSecureQueries() {
        return secureQueries;
    }

    private static UserRecord mapRow(ResultSet rs) throws java.sql.SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getString("password_hash"));
    }
}
