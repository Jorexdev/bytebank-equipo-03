package dev.bytebank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTest {

    @Autowired
    HttpClientProperties httpClientProperties;

    @Autowired
    GatewayProperties gatewayProperties;

    @Autowired
    WebTestClient webTestClient;

    @Test
    void configuresHttpClientTimeoutsAndRemovesInternalHeader() {
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(2000);
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(gatewayProperties.getDefaultFilters())
                .anySatisfy(filter -> {
                    assertThat(filter.getName()).isEqualTo("RemoveRequestHeader");
                    assertThat(filter.getArgs()).containsValue("X-Internal-Key");
                });
    }

    @Test
    void exposesOnlyTheSixPublicApiRoutes() {
        assertThat(gatewayProperties.getRoutes())
                .extracting(route -> route.getId())
                .containsExactlyInAnyOrder("auth", "accounts", "transfers", "cards", "fraud", "notifications");
    }

    @Test
    void blocksInternalPathsWithNotFound() {
        webTestClient.get()
                .uri("/internal/accounts/ES7639990001000000000001")
                .header("X-Internal-Key", "cualquier-clave")
                .exchange()
                .expectStatus().isNotFound();
    }
}
