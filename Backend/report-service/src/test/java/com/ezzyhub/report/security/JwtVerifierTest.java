package com.ezzyhub.report.security;

import com.ezzyhub.report.exception.UnauthorizedException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtVerifier}: a valid signed token yields the user id,
 * and every malformed/forged/expired case is rejected with UnauthorizedException.
 */
class JwtVerifierTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes-long-1234";
    private final JwtVerifier verifier = new JwtVerifier(SECRET);

    private static SecretKey key(String secret) {
        byte[] b = secret.getBytes(StandardCharsets.UTF_8);
        if (b.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(b, 0, padded, 0, b.length);
            b = padded;
        }
        return Keys.hmacShaKeyFor(b);
    }

    private static String token(String secret, Long userId, long expiresInMs) {
        var builder = Jwts.builder().signWith(key(secret));
        if (userId != null) builder.claim("userId", userId);
        return builder
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiresInMs))
                .compact();
    }

    @Test
    void extractsUserId_fromValidToken() {
        String jwt = token(SECRET, 42L, 60_000);
        assertThat(verifier.extractUserId("Bearer " + jwt)).isEqualTo("42");
    }

    @Test
    void rejectsMissingHeader() {
        assertThatThrownBy(() -> verifier.extractUserId(null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Missing or malformed");
    }

    @Test
    void rejectsNonBearerHeader() {
        assertThatThrownBy(() -> verifier.extractUserId("Basic abc123"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsGarbageToken() {
        assertThatThrownBy(() -> verifier.extractUserId("Bearer not-a-real-jwt"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid or expired");
    }

    @Test
    void rejectsTokenSignedWithWrongSecret() {
        String forged = token("a-totally-different-secret-key-value-32bytes!!", 7L, 60_000);
        assertThatThrownBy(() -> verifier.extractUserId("Bearer " + forged))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String expired = token(SECRET, 5L, -1_000);   // already expired
        assertThatThrownBy(() -> verifier.extractUserId("Bearer " + expired))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokenMissingUserIdClaim() {
        String noClaim = token(SECRET, null, 60_000);
        assertThatThrownBy(() -> verifier.extractUserId("Bearer " + noClaim))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("userId");
    }
}
