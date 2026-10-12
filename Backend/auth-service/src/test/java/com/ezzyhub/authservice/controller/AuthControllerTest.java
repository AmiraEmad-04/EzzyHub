package com.ezzyhub.authservice.controller;

import com.ezzyhub.authservice.dto.AuthResponse;
import com.ezzyhub.authservice.dto.LoginRequest;
import com.ezzyhub.authservice.dto.RegisterRequest;
import com.ezzyhub.authservice.dto.UserDto;
import com.ezzyhub.authservice.exception.EmailAlreadyExistsException;
import com.ezzyhub.authservice.exception.InvalidCredentialsException;
import com.ezzyhub.authservice.exception.UnauthorizedException;
import com.ezzyhub.authservice.security.RateLimiter;
import com.ezzyhub.authservice.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AuthService authService;
    @MockBean RateLimiter rateLimiter;

    @BeforeEach
    void allowAllByDefault() {
        // Individual tests for the rate limiter itself override this with a stricter stub.
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
    }

    private UserDto sampleUser() {
        return UserDto.builder()
                .id(1L)
                .fullName("Buzz Bee")
                .email("buzz@ezzyhub.com")
                .role("USER")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("POST /register returns 201 with a token for a new email")
    void registerReturns201() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .token("fake.jwt.token")
                .tokenType("Bearer")
                .expiresIn(604800)
                .user(sampleUser())
                .build();
        when(authService.register(any())).thenReturn(response);

        RegisterRequest req = new RegisterRequest("Buzz Bee", "buzz@ezzyhub.com", "honeycomb1");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("fake.jwt.token"))
                .andExpect(jsonPath("$.user.email").value("buzz@ezzyhub.com"));
    }

    @Test
    @DisplayName("POST /register returns 409 when the email is already taken")
    void registerReturns409ForDuplicateEmail() throws Exception {
        when(authService.register(any())).thenThrow(new EmailAlreadyExistsException("buzz@ezzyhub.com"));

        RegisterRequest req = new RegisterRequest("Buzz Bee", "buzz@ezzyhub.com", "honeycomb1");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /register returns 400 for an invalid email")
    void registerReturns400ForInvalidEmail() throws Exception {
        RegisterRequest req = new RegisterRequest("Buzz Bee", "not-an-email", "honeycomb1");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /login returns 200 with a token for correct credentials")
    void loginReturns200() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .token("fake.jwt.token")
                .tokenType("Bearer")
                .expiresIn(604800)
                .user(sampleUser())
                .build();
        when(authService.login(any())).thenReturn(response);

        LoginRequest req = new LoginRequest("buzz@ezzyhub.com", "honeycomb1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake.jwt.token"));
    }

    @Test
    @DisplayName("POST /login returns 401 for wrong password")
    void loginReturns401ForBadCredentials() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        LoginRequest req = new LoginRequest("buzz@ezzyhub.com", "wrong-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me returns the current user for a valid Bearer token")
    void meReturnsCurrentUser() throws Exception {
        when(authService.getCurrentUser(eq("Bearer fake.jwt.token"))).thenReturn(sampleUser());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer fake.jwt.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("buzz@ezzyhub.com"));
    }

    @Test
    @DisplayName("GET /me returns 401 with no Authorization header")
    void meReturns401WithoutToken() throws Exception {
        when(authService.getCurrentUser(null)).thenThrow(new UnauthorizedException("Missing or malformed Authorization header"));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /login returns 429 once the rate limiter denies the request")
    void loginReturns429WhenRateLimited() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        LoginRequest req = new LoginRequest("buzz@ezzyhub.com", "honeycomb1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("POST /register returns 429 once the rate limiter denies the request")
    void registerReturns429WhenRateLimited() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RegisterRequest req = new RegisterRequest("Buzz Bee", "buzz@ezzyhub.com", "honeycomb1");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("GET /health reports UP")
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/api/v1/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.module").value("Auth Service"));
    }
}
