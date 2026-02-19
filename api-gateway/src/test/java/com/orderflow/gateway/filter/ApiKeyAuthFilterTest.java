package com.orderflow.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock GatewayFilterChain chain;

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter("valid-key-1,valid-key-2");
    }

    @Test
    void validApiKey_passesRequestToChain() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders")
                        .header("X-API-Key", "valid-key-1")
                        .build());
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void secondValidKey_alsoPassesThrough() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/orders")
                        .header("X-API-Key", "valid-key-2")
                        .build());
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void missingApiKey_returns401AndDoesNotCallChain() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void invalidApiKey_returns401AndDoesNotCallChain() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders")
                        .header("X-API-Key", "wrong-key-xyz")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void actuatorPath_bypassesAuthCompletely() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // No API key required — chain must be called
        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void filterOrder_isHigherPriorityThanRateLimiter() {
        // Rate limiter has order=1; this filter must run before it
        assertThat(filter.getOrder()).isLessThan(1);
    }

    @Test
    void filterOrder_isNegativeHundred() {
        assertThat(filter.getOrder()).isEqualTo(-100);
    }
}
