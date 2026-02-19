package com.orderflow.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global pre-filter that enforces API key authentication on all routes.
 *
 * <p>Requests to {@code /actuator/**} are exempted so health probes and
 * metrics scrapers don't require a key.
 *
 * <p>Order {@code -100} places this filter before the Spring Cloud Gateway
 * rate-limiter ({@code order = 1}) so unauthenticated requests are rejected
 * before consuming rate-limit tokens.
 *
 * <p>In production the valid key set would be fetched from AWS Secrets Manager
 * (via the {@code AWS_SECRETSMANAGER} Spring Cloud Config backend or a
 * {@code @RefreshScope} bean) rather than a static environment variable.
 */
@Slf4j
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private static final String HEADER = "X-API-Key";
    private static final String ERROR_BODY =
            "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-API-Key header\"}";

    private final Set<String> validApiKeys;

    public ApiKeyAuthFilter(@Value("${app.api-keys:dev-key-1}") String apiKeysConfig) {
        this.validApiKeys = Arrays.stream(apiKeysConfig.split(","))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Actuator endpoints (health probes, metrics) bypass auth
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String apiKey = exchange.getRequest().getHeaders().getFirst(HEADER);

        if (apiKey == null || apiKey.isBlank() || !validApiKeys.contains(apiKey.trim())) {
            log.warn("Rejected request: invalid or missing {} for path={}", HEADER, path);
            return writeUnauthorized(exchange.getResponse());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100; // Runs before rate limiter (order=1)
    }

    private Mono<Void> writeUnauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        DataBuffer buffer = response.bufferFactory()
                .wrap(ERROR_BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
