package com.example.ezzyhubai.security;



import com.example.ezzyhubai.exception.PromptInjectionException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PromptSecurityService {

    private static final List<String> BLOCKED_PATTERNS =
            List.of(
                    "ignore previous instructions",
                    "ignore all instructions",
                    "forget previous instructions",
                    "reveal system prompt",
                    "show system prompt",
                    "print system prompt",
                    "developer instructions",
                    "bypass security",
                    "delete database",
                    "drop table",
                    "execute command",
                    "run shell",
                    "sudo",
                    "rm -rf",
                    "disable safety",
                    "you are now",
                    "act as root",
                    "jailbreak"
            );

    public void validate(String text) {

        if (text == null || text.isBlank()) {
            return;
        }

        String lower = text.toLowerCase();

        for (String pattern : BLOCKED_PATTERNS) {

            if (lower.contains(pattern)) {
                throw new PromptInjectionException(
                        "Prompt injection attempt detected.");
            }
        }
    }
}