package com.homeexpress.home_express_api.service.intake;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.homeexpress.home_express_api.BaseIntegrationTest;
import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        // Disable cache for deterministic calls
        "spring.cache.type=NONE",
        // Short timeouts to force quick failures in tests
        "http.client.intake-ai.connect-timeout-ms=200",
        "http.client.intake-ai.read-timeout-ms=500",
        // Retry configuration for intake-ai
        "resilience4j.retry.instances.intake-ai.max-attempts=3",
        "resilience4j.retry.instances.intake-ai.wait-duration=200ms",
        "resilience4j.retry.instances.intake-ai.enable-exponential-backoff=false",
        // Circuit breaker configuration for intake-ai
        "resilience4j.circuitbreaker.instances.intake-ai.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.intake-ai.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.intake-ai.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.intake-ai.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.intake-ai.wait-duration-in-open-state=30s",
        "resilience4j.circuitbreaker.instances.intake-ai.permitted-number-of-calls-in-half-open-state=1"
})
class IntakeAIParsingServiceIntegrationTest extends BaseIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private IntakeAIParsingService intakeAIParsingService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("openai.api.key", () -> "test-key");
        registry.add("openai.api.url", () -> wireMockServer.baseUrl() + "/v1/chat/completions");
    }

    @BeforeEach
    void resetServers() {
        wireMockServer.resetAll();
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("intake-ai");
        cb.reset();
    }

    @Test
    void shouldRetryThenFallbackOnTimeout() {
        // WireMock responds slower than read-timeout to trigger ResourceAccessException
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{}")
                        .withFixedDelay(1500)));

        List<IntakeParseTextResponse.ParsedItem> result = intakeAIParsingService.parseWithAI("1 sofa");

        assertThat(result).isNotEmpty();
        // 2 HTTP attempts (initial + 1 retry) before CircuitBreaker opens and blocks the 3rd
        WireMock.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void shouldOpenCircuitAndShortCircuitSubsequentCalls() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{}")
                        .withFixedDelay(1500)));

        // First two calls fail (timeouts) and should open circuit breaker
        intakeAIParsingService.parseWithAI("case-" + UUID.randomUUID());
        intakeAIParsingService.parseWithAI("case-" + UUID.randomUUID());

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("intake-ai");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        wireMockServer.resetRequests();

        // Third call should be short-circuited (no HTTP call) and use fallback
        List<IntakeParseTextResponse.ParsedItem> result = intakeAIParsingService.parseWithAI("case-" + UUID.randomUUID());

        assertThat(result).isNotEmpty();
        WireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }
}
