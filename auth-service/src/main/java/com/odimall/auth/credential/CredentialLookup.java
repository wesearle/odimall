package com.odimall.auth.credential;

import com.odimall.auth.model.UserRecord;

import java.util.List;

/**
 * Return value of {@link CredentialStore#findByUsername}. This is a prime Odigos
 * custom-instrumentation target: capturing it as a span's return value shows the
 * exact SQL that ran and how many rows came back — a normal login matches 0 or 1,
 * a tautology injection matches the whole table.
 */
public class CredentialLookup {
    private final List<UserRecord> rows;
    private final String sql;

    public CredentialLookup(List<UserRecord> rows, String sql) {
        this.rows = rows;
        this.sql = sql;
    }

    public List<UserRecord> getRows() { return rows; }
    public String getSql() { return sql; }
    public int getMatchedRows() { return rows.size(); }

    /** True when the lookup returned more than one row — impossible for a real
     *  single-username lookup, so it marks a SQL-injection dump. */
    public boolean isBreach() { return rows.size() > 1; }

    @Override
    public String toString() {
        return "CredentialLookup{matchedRows=" + rows.size()
                + ", breach=" + isBreach()
                + ", sql=\"" + sql + "\"}";
    }
}
