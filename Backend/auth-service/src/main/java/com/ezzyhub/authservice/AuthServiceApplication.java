package com.ezzyhub.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EzzyHub Auth Service
 *
 * Owns account creation and login by email for the whole platform.
 * Stores users in the `users` table of the shared ezzyhub_db MySQL database
 * (the same database requirement-analyzer already uses).
 *
 * Issues a JWT on register/login. Other services can verify that JWT later
 * by sharing the same signing secret (see application.properties) — that
 * wiring is a separate step once the gateway route is live.
 */
@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
