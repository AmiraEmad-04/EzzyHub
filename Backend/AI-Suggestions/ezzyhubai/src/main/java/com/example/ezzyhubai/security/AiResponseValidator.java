package com.example.ezzyhubai.security;

import com.example.ezzyhubai.exception.GenerationException;
import org.springframework.stereotype.Service;

@Service
public class AiResponseValidator {

    public void validate(String response) {

        if (response == null
                || response.isBlank()) {

            throw new GenerationException(
                    "AI returned an empty response.");
        }

        if (response.contains("NO_CHANGES_REQUIRED")
                || response.contains("INSUFFICIENT_CONTEXT")) {
            return;
        }

        if (!response.contains("=== FILE ===")
                || !response.contains("=== CODE ===")
                || !response.contains("=== END ===")) {

            throw new GenerationException(
                    "AI returned an invalid response.");
        }
    }
}