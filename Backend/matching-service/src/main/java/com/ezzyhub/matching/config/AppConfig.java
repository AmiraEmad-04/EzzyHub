package com.ezzyhub.matching.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Application configuration: WebClient beans for calling Python NLP and
 * Requirement Analyzer services.
 *
 * Large buffer sizes are required because:
 *   - /ast-chunk-zip can return hundreds of chunks × 384 floats each
 *   - /preprocess-and-embed returns 384-float arrays
 *   - Default WebClient buffer is 256 KB — far too small
 */
@Configuration
public class AppConfig {

    // 128 MB buffer — handles large code projects with many chunks
    private static final int MAX_MEMORY_SIZE = 128 * 1024 * 1024;

    @Value("${python.nlp.base-url:http://localhost:8000}")
    private String pythonBaseUrl;

    @Value("${requirement.analyzer.base-url:http://localhost:8081}")
    private String requirementAnalyzerBaseUrl;

    // Python service has long startup and AST chunking can be slow for large projects
    @Value("${python.nlp.timeout-seconds:120}")
    private int pythonTimeoutSeconds;

    @Value("${requirement.analyzer.timeout-seconds:30}")
    private int reqAnalyzerTimeoutSeconds;

    @Value("${code.analyzer.base-url:http://localhost:8080}")
    private String codeAnalyzerBaseUrl;

    // Code-Analyzer runs an LLM over the whole project, so allow generous time.
    @Value("${code.analyzer.timeout-seconds:300}")
    private int codeAnalyzerTimeoutSeconds;

    @Bean(name = "pythonWebClient")
    public WebClient pythonWebClient() {
        return buildWebClient(pythonBaseUrl, pythonTimeoutSeconds);
    }

    @Bean(name = "requirementAnalyzerWebClient")
    public WebClient requirementAnalyzerWebClient() {
        return buildWebClient(requirementAnalyzerBaseUrl, reqAnalyzerTimeoutSeconds);
    }

    @Bean(name = "codeAnalyzerWebClient")
    public WebClient codeAnalyzerWebClient() {
        return buildWebClient(codeAnalyzerBaseUrl, codeAnalyzerTimeoutSeconds);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private WebClient buildWebClient(String baseUrl, int timeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(this::setMaxBufferSize)
                .build();

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    private void setMaxBufferSize(ClientCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(MAX_MEMORY_SIZE);
    }
}