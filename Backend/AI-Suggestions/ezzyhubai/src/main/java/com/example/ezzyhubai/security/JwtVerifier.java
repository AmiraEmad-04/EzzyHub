package com.example.ezzyhubai.security;

import com.example.ezzyhubai.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies the JWT that auth-service issues on login/register.
 *
 * AI-Suggestions only verifies tokens here — it never issues them. Signing
 * remains entirely auth-service's responsibility. Same pattern as
 * report-service's / Requirement-Analyzer's JwtVerifier, reusing the same
 * shared auth.jwt.secret.
 */
@Component
public class JwtVerifier {

    private final SecretKey signingKey;

    public JwtVerifier(@Value("${auth.jwt.secret}") String secret) {
        // Same padding logic as auth-service's JwtUtil, so a short override
        // secret can't crash this service on startup either.
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Extracts and verifies the user id from an `Authorization: Bearer <token>`
     * header. Throws UnauthorizedException for anything wrong with it —
     * missing header, malformed value, bad signature, or expired token.
     */
    public String extractUserId(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Missing or malformed Authorization header");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        Long userId = claims.get("userId", Long.class);
        if (userId == null) {
            throw new UnauthorizedException("Token missing userId claim");
        }
        return String.valueOf(userId);
    }
}
