package com.ezzyhub.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    @Builder.Default
    private String tokenType = "Bearer";
    /** Seconds until the token expires, so the frontend can plan a refresh/re-login. */
    private long expiresIn;
    private UserDto user;
}
