package com.odimall.auth.auth;

import com.odimall.auth.model.UserRecord;

import java.util.List;

/**
 * Return value of {@link AuthService#authenticate}. This is the headline Odigos
 * custom-instrumentation target: capturing it as a span's return value tells the
 * whole story of an attack without reading logs —
 *
 *   outcome=SQLI_BREACH, rowsReturned=4, passwordVerified=false, bypassedAs=admin
 *
 * i.e. a successful login where the password was never checked and the lookup
 * returned the entire table.
 */
public class AuthResult {

    public enum Outcome { SUCCESS, SQLI_BREACH, INVALID_CREDENTIALS }

    private final Outcome outcome;
    private final int httpStatus;
    private final UserRecord user;          // authenticated (or bypassed) user; null on failure
    private final boolean passwordVerified; // false for the SQLi bypass path
    private final int rowsReturned;         // from the credential lookup
    private final boolean anomalousOrigin;
    private final List<UserRecord> exfiltrated; // non-null only on a breach
    private final String token;             // non-null on success/breach
    private final String effectiveSql;

    private AuthResult(Outcome outcome, int httpStatus, UserRecord user, boolean passwordVerified,
                       int rowsReturned, boolean anomalousOrigin, List<UserRecord> exfiltrated,
                       String token, String effectiveSql) {
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.user = user;
        this.passwordVerified = passwordVerified;
        this.rowsReturned = rowsReturned;
        this.anomalousOrigin = anomalousOrigin;
        this.exfiltrated = exfiltrated;
        this.token = token;
        this.effectiveSql = effectiveSql;
    }

    public static AuthResult success(UserRecord user, String token, boolean anomalousOrigin,
                                     int rowsReturned, String sql) {
        return new AuthResult(Outcome.SUCCESS, 200, user, true, rowsReturned, anomalousOrigin,
                null, token, sql);
    }

    public static AuthResult breach(UserRecord bypassedAs, String token, int rowsReturned,
                                    List<UserRecord> exfiltrated, boolean anomalousOrigin, String sql) {
        return new AuthResult(Outcome.SQLI_BREACH, 200, bypassedAs, false, rowsReturned,
                anomalousOrigin, exfiltrated, token, sql);
    }

    public static AuthResult invalid() {
        return new AuthResult(Outcome.INVALID_CREDENTIALS, 401, null, false, 0, false, null, null, null);
    }

    public Outcome getOutcome() { return outcome; }
    public int getHttpStatus() { return httpStatus; }
    public UserRecord getUser() { return user; }
    public boolean isPasswordVerified() { return passwordVerified; }
    public int getRowsReturned() { return rowsReturned; }
    public boolean isAnomalousOrigin() { return anomalousOrigin; }
    public List<UserRecord> getExfiltrated() { return exfiltrated; }
    public String getToken() { return token; }
    public String getEffectiveSql() { return effectiveSql; }
    public boolean isOk() { return outcome != Outcome.INVALID_CREDENTIALS; }
    public boolean isBreach() { return outcome == Outcome.SQLI_BREACH; }

    @Override
    public String toString() {
        return "AuthResult{outcome=" + outcome
                + ", httpStatus=" + httpStatus
                + ", user=" + (user == null ? "null" : user.getUsername())
                + ", role=" + (user == null ? "null" : user.getRole())
                + ", passwordVerified=" + passwordVerified
                + ", rowsReturned=" + rowsReturned
                + ", anomalousOrigin=" + anomalousOrigin
                + ", exfiltratedCount=" + (exfiltrated == null ? 0 : exfiltrated.size())
                + "}";
    }
}
