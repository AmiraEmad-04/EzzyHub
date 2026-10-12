package com.ezzyhub.authservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public OpenAPI openApiConfig() {
        return new OpenAPI()
                .info(new Info()
                        .title("EzzyHub Auth Service API")
                        .description("Account creation and login by email for the EzzyHub platform.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("EzzyHub Team")
                                .email("team@ezzyhub.com"))
                        .license(new License().name("Private — Graduation Project")));
    }
}
