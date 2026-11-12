package com.odimall.auth.model;

/**
 * One row from the `users` table. Carries the bcrypt hash so the credential-dump
 * breach can show exactly what an attacker exfiltrates.
 */
public class UserRecord {
    private final long id;
    private final String username;
    private final String email;
    private final String role;
    private final String passwordHash;

    public UserRecord(long id, String username, String email, String role, String passwordHash) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public String getPasswordHash() { return passwordHash; }

    @Override
    public String toString() {
        // No hash in toString — keep span-friendly, non-sensitive by default.
        return "UserRecord{id=" + id + ", username='" + username + "', role='" + role + "'}";
    }
}
