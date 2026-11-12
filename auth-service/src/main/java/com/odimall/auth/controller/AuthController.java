package com.odimall.auth.controller;

import com.odimall.auth.auth.AuthResult;
import com.odimall.auth.auth.AuthService;
import com.odimall.auth.auth.JwtService;
import com.odimall.auth.credential.CredentialStore;
import com.odimall.auth.model.AnomalyContext;
import com.odimall.auth.model.UserRecord;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface. Mirrors the previous Node service exactly so the frontend,
 * Red Team panel, console helper, load generator, and gateway routing are unchanged.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final CredentialStore credentialStore;
    private final JwtService jwtService;

    public AuthController(AuthService authService, CredentialStore credentialStore, JwtService jwtService) {
        this.authService = authService;
        this.credentialStore = credentialStore;
        this.jwtService = jwtService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            long n = credentialStore.countUsers();
            body.put("status", "ok");
            body.put("users", n);
            body.put("secureQueries", credentialStore.isSecureQueries());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "degraded");
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> req,
                                                     HttpServletRequest http) {
        Object usernameObj = req == null ? null : req.get("username");
        if (usernameObj == null) {
            return error(HttpStatus.BAD_REQUEST, "username is required");
        }
        String username = String.valueOf(usernameObj);
        String password = req.get("password") == null ? null : String.valueOf(req.get("password"));
        AnomalyContext ctx = anomalyContext(http);

        try {
            AuthResult result = authService.authenticate(username, password, ctx);
            if (!result.isOk()) {
                return error(HttpStatus.UNAUTHORIZED, "Invalid username or password");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", result.getToken());
            body.put("user", publicUser(result.getUser()));
            if (result.isBreach()) {
                body.put("warning", "multiple credential rows returned for a single username lookup");
                body.put("rowsReturned", result.getRowsReturned());
                body.put("exfiltrated", exfiltrated(result.getExfiltrated()));
            }
            if (result.isAnomalousOrigin()) {
                body.put("anomalousOrigin", true);
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("login ERROR user=\"{}\" err={}", username, e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "authentication failed");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
        if (token == null) {
            return error(HttpStatus.UNAUTHORIZED, "missing token");
        }
        try {
            Claims claims = jwtService.verify(token);
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", claims.getSubject());
            user.put("username", claims.get("username"));
            user.put("role", claims.get("role"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user", user);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return error(HttpStatus.UNAUTHORIZED, "invalid token");
        }
    }

    private AnomalyContext anomalyContext(HttpServletRequest req) {
        String xff = req.getHeader("x-forwarded-for");
        String clientIp;
        if (xff != null && !xff.isBlank()) {
            clientIp = xff.split(",")[0].trim();
        } else {
            clientIp = req.getRemoteAddr();
        }
        String userAgent = req.getHeader("user-agent");
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "unknown";
        }
        boolean anomalous = "anomalous".equalsIgnoreCase(String.valueOf(req.getHeader("x-odimall-origin")));
        return new AnomalyContext(clientIp == null ? "unknown" : clientIp, userAgent, anomalous);
    }

    private static Map<String, Object> publicUser(UserRecord u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        return m;
    }

    private static List<Map<String, Object>> exfiltrated(List<UserRecord> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserRecord r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", r.getUsername());
            m.put("role", r.getRole());
            m.put("email", r.getEmail());
            m.put("passwordHash", r.getPasswordHash()); // leaked credential material — the breach
            out.add(m);
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
