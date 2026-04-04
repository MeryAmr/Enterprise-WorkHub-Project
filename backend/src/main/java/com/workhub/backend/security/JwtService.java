package com.workhub.backend.security;

import com.workhub.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiration}") long expiration) {
        this.secretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expiration = expiration;
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(now)
                .expiration(expiryDate);

        if (user.getTenant() != null) {
            builder.claim("tenantId", user.getTenant().getId().toString());
        }
        if (user.getRole() != null) {
            builder.claim("role", user.getRole().name());
        }

        return builder.signWith(secretKey).compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public UUID extractTenantId(String token) {
        String tenantId = parseClaims(token).get("tenantId", String.class);
        return tenantId != null ? UUID.fromString(tenantId) : null;
    }

    public TokenValidationResult validateToken(String token) {
        try {
            parseClaims(token);
            return TokenValidationResult.success();
        } catch (ExpiredJwtException ex) {
            return TokenValidationResult.expiredToken();
        } catch (JwtException | IllegalArgumentException ex) {
            return TokenValidationResult.invalidToken();
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record TokenValidationResult(boolean valid, String errorMessage) {
        public static TokenValidationResult success() {
            return new TokenValidationResult(true, null);
        }

        public static TokenValidationResult expiredToken() {
            return new TokenValidationResult(false, "Token expired");
        }

        public static TokenValidationResult invalidToken() {
            return new TokenValidationResult(false, "Invalid authentication token");
        }
    }
}
