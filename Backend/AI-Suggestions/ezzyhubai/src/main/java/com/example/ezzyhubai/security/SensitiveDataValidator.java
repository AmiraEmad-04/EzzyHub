package com.example.ezzyhubai.security;

import com.example.ezzyhubai.exception.GenerationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SensitiveDataValidator {

    private static final List<String> BLOCKED =
            List.of(
                    "sk-",
                    "OPENAI_API_KEY",
                    "GROQ_API_KEY",
                    "password=",
                    "jdbc:mysql://",
                    "jdbc:postgresql://",
                    "spring.datasource.password",
                    "jwt.secret",
                    "api_key",
                    "secret_key",
                    "access_token"
            );

    public void validate(String text) {

        if (text == null) {
            return;
        }

        String lower =
                text.toLowerCase();

        for (String pattern :
                BLOCKED) {

            if (lower.contains(
                    pattern.toLowerCase())) {

                throw new GenerationException(
                        "Sensitive data detected.");
            }
        }
    }
}