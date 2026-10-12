package com.codeanalyzer.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private Dotenv dotenv;

    @PostConstruct
    public void init() {
        try {
            dotenv = Dotenv.configure()
                    .ignoreIfMissing()
                    .load();
            log.info("Loaded .env configuration");
        } catch (DotenvException e) {
            log.warn(".env file not found or could not be loaded, using system environment variables only");
            dotenv = Dotenv.configure().ignoreIfMissing().load();
        }
    }

    public String get(String key) {
        String val = dotenv.get(key);
        if (val == null || val.isBlank()) {
            val = System.getenv(key);
        }
        return val;
    }

    public String get(String key, String defaultValue) {
        String val = get(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        try {
            String val = get(key);
            return (val != null) ? Integer.parseInt(val.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        try {
            String val = get(key);
            return (val != null) ? Long.parseLong(val.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
