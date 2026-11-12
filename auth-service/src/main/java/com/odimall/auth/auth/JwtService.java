package com.odimall.auth.auth;

import com.odimall.auth.model.UserRecord;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

/** Issues and verifies the session JWTs. HS256 over a key derived from the
 *  configured secret (SHA-256 so any secret length yields a valid 256-bit key). */
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${auth.jwt-secret:odimall-demo-signing-key}") String secret,
                      @Value("${auth.token-ttl-minutes:120}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(sha256(secret));
        this.ttlMillis = ttlMinutes * 60_000L;
    }

    public String sign(UserRecord user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
