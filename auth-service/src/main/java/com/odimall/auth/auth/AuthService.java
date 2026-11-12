package com.odimall.auth.auth;

import com.odimall.auth.credential.CredentialLookup;
import com.odimall.auth.credential.CredentialStore;
import com.odimall.auth.model.AnomalyContext;
import com.odimall.auth.model.UserRecord;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Authentication decision logic.
 *
 * <p><b>Odigos custom-instrumentation target.</b> Instrument
 * {@link #authenticate(String, String, AnomalyContext)}: the ARGUMENTS carry the
 * submitted username (the attacker's payload) and the origin context, and the
 * RETURN VALUE ({@link AuthResult}) states the outcome — SUCCESS, SQLI_BREACH, or
 * INVALID_CREDENTIALS — along with whether a password was actually verified. That
 * return value is where "a login that succeeded without a password" becomes visible.
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final CredentialStore credentialStore;
    private final JwtService jwtService;

    public AuthService(CredentialStore credentialStore, JwtService jwtService) {
        this.credentialStore = credentialStore;
        this.jwtService = jwtService;
    }

    public AuthResult authenticate(String username, String password, AnomalyContext ctx) {
        CredentialLookup lookup = credentialStore.findByUsername(username);

        if (lookup.getMatchedRows() == 0) {
            logger.info("login FAILED user=\"{}\" reason=no_match ip={}", username, ctx.getClientIp());
            return AuthResult.invalid();
        }

        // Breach: a single-username lookup should never match more than one row.
        // When it does, an injected tautology dumped the credential table — and the
        // naive service trusts the first row (SQLi auth bypass) with no password.
        if (lookup.isBreach()) {
            UserRecord victim = lookup.getRows().get(0);
            logger.warn("SQLI BREACH rows={} exfiltrated={} bypass_as={} role={} ip={} ua=\"{}\" statement=\"{}\"",
                    lookup.getMatchedRows(), lookup.getMatchedRows(), victim.getUsername(), victim.getRole(),
                    ctx.getClientIp(), ctx.getUserAgent(), lookup.getSql());
            String token = jwtService.sign(victim);
            return AuthResult.breach(victim, token, lookup.getMatchedRows(), lookup.getRows(),
                    ctx.isAnomalous(), lookup.getSql());
        }

        // Normal single-row path: verify the bcrypt hash.
        UserRecord user = lookup.getRows().get(0);
        boolean valid = safeCheck(password, user.getPasswordHash());
        if (!valid) {
            logger.info("login FAILED user=\"{}\" reason=bad_password ip={}", username, ctx.getClientIp());
            return AuthResult.invalid();
        }

        logger.info("login OK user={} role={} ip={} ua=\"{}\"{}",
                user.getUsername(), user.getRole(), ctx.getClientIp(), ctx.getUserAgent(),
                ctx.isAnomalous() ? " ORIGIN=anomalous (possible stolen credential)" : "");
        return AuthResult.success(user, jwtService.sign(user), ctx.isAnomalous(),
                lookup.getMatchedRows(), lookup.getSql());
    }

    private static boolean safeCheck(String password, String hash) {
        try {
            return hash != null && !hash.isEmpty()
                    && BCrypt.checkpw(password == null ? "" : password, hash);
        } catch (IllegalArgumentException e) {
            // Malformed hash stored — treat as a failed login rather than a 500.
            return false;
        }
    }
}
