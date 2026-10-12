package com.ezzyhub.authservice.service;

import com.ezzyhub.authservice.dto.AuthResponse;
import com.ezzyhub.authservice.dto.LoginRequest;
import com.ezzyhub.authservice.dto.RegisterRequest;
import com.ezzyhub.authservice.dto.UserDto;
import com.ezzyhub.authservice.exception.EmailAlreadyExistsException;
import com.ezzyhub.authservice.exception.InvalidCredentialsException;
import com.ezzyhub.authservice.exception.UnauthorizedException;
import com.ezzyhub.authservice.model.User;
import com.ezzyhub.authservice.repository.UserRepository;
import com.ezzyhub.authservice.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role("USER")
                .build();

        user = userRepository.save(user);
        log.info("Registered new user id={} email={}", user.getId(), user.getEmail());

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        log.info("Login succeeded for user id={} email={}", user.getId(), user.getEmail());
        return buildAuthResponse(user);
    }

    /**
     * Resolves the current user from an `Authorization: Bearer <token>` header.
     * Used by GET /me. Throws UnauthorizedException on anything wrong with the
     * token (missing, malformed, expired, signed with a different secret, or
     * pointing at a user that no longer exists).
     */
    public UserDto getCurrentUser(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        Long userId = jwtUtil.extractUserId(claims);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User no longer exists"));

        return UserDto.from(user);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Missing or malformed Authorization header");
        }
        return authorizationHeader.substring("Bearer ".length()).trim();
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getExpirationSeconds())
                .user(UserDto.from(user))
                .build();
    }
}
