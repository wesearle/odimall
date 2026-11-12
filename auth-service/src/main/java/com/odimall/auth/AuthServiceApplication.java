package com.odimall.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OdiMall auth-service.
 *
 * A deliberately-instrumentable authentication service built to showcase Odigos
 * trace-anomaly detection for a security product. Written in Java specifically so
 * Odigos custom instrumentation can capture method ARGUMENTS and RETURN VALUES in
 * spans — the attacker's injected input surfaces as the argument to
 * {@code CredentialStore.findByUsername}, and the breach decision surfaces as the
 * return value of {@code AuthService.authenticate}.
 *
 * Two-phase attack this reproduces:
 *   1. Credential theft — the login username lookup is intentionally SQL-injectable;
 *      a tautology dumps the whole users table (anomalous row count + db.statement).
 *   2. Stolen-credential login — a valid login for a victim from an anomalous origin.
 *
 * This is a demo. The vulnerability is the point. Set AUTH_SECURE_QUERIES=true for
 * the remediated (parameterized) path.
 */
@SpringBootApplication
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
