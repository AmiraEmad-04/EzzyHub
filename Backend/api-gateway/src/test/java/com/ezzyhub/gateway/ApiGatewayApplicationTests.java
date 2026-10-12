package com.ezzyhub.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot tests for the API gateway. Starts the full reactive context on a random
 * port (no downstream services required). Verifies context load, the aggregate
 * health controller (five downstream services), and the routes declared in
 * application.yml with their expected predicates and target ports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Test
    void contextLoads() {
        assertThat(routeDefinitionLocator).isNotNull();
    }

    @Test
    void aggregateHealth_returnsGatewayUp_andAllFiveServiceStatuses() {
        client.get().uri("/api/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.gateway").isEqualTo("UP")
                .jsonPath("$.services.code-analyzer").exists()
                .jsonPath("$.services.requirement-analyzer").exists()
                .jsonPath("$.services.python-nlp").exists()
                .jsonPath("$.services.auth-service").exists()
                .jsonPath("$.services.sonar-analyzer").exists()
                // downstream services are not running in the test → DOWN, so not all up
                .jsonPath("$.allUp").isEqualTo(false)
                .jsonPath("$.services.code-analyzer.status").isEqualTo("DOWN");
    }

    @Test
    void routeDefinitions_containExpectedServicesWithCorrectPorts() {
        Map<String, RouteDefinition> byId = routes();

        assertThat(byId).containsKeys(
                "code-analyzer",
                "requirement-analyzer-requirements",
                "matching-service-match",
                "sonar-analyzer",
                "python-nlp",
                "report-service",
                "auth-service",
                "gateway-health");

        assertThat(byId.get("code-analyzer").getUri().getPort()).isEqualTo(8080);
        assertThat(byId.get("requirement-analyzer-requirements").getUri().getPort()).isEqualTo(8081);
        assertThat(byId.get("matching-service-match").getUri().getPort()).isEqualTo(8083);
        assertThat(byId.get("sonar-analyzer").getUri().getPort()).isEqualTo(8084);
        assertThat(byId.get("python-nlp").getUri().getPort()).isEqualTo(8000);
        assertThat(byId.get("report-service").getUri().getPort()).isEqualTo(8086);
        assertThat(byId.get("auth-service").getUri().getPort()).isEqualTo(8087);
    }

    @Test
    void routeDefinitions_haveExpectedPathPredicates() {
        Map<String, RouteDefinition> byId = routes();

        assertPathPredicate(byId.get("code-analyzer"), "/api/code/**");
        assertPathPredicate(byId.get("requirement-analyzer-requirements"), "/api/requirements/**");
        assertPathPredicate(byId.get("matching-service-match"), "/api/match");
        assertPathPredicate(byId.get("sonar-analyzer"), "/api/sonar/**");
        assertPathPredicate(byId.get("python-nlp"), "/api/nlp/**");
        assertPathPredicate(byId.get("report-service"), "/api/history/**");
        assertPathPredicate(byId.get("auth-service"), "/api/auth/**");
        assertPathPredicate(byId.get("gateway-health"), "/api/health");
    }

    private Map<String, RouteDefinition> routes() {
        List<RouteDefinition> defs = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(defs).isNotNull();
        return defs.stream().collect(Collectors.toMap(RouteDefinition::getId, r -> r, (a, b) -> a));
    }

    private static void assertPathPredicate(RouteDefinition route, String expectedPath) {
        assertThat(route).isNotNull();
        assertThat(route.getPredicates()).anySatisfy(p -> {
            assertThat(p.getName()).isEqualTo("Path");
            assertThat(p.getArgs().values()).contains(expectedPath);
        });
    }
}
