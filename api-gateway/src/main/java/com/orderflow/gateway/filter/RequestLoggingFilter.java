package com.orderflow.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Outermost global filter — logs every request with method, path, masked API key,
 * final HTTP status, and round-trip duration.
 *
 * <p>Order {@code -200} ensures this runs before {@link ApiKeyAuthFilter} ({@code -100})
 * so even rejected requests (401) are logged.
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startMs = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        String method   = request.getMethod().name();
        String path     = request.getPath().value();
        String maskedKey = maskApiKey(request.getHeaders().getFirst("X-API-Key"));

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = System.currentTimeMillis() - startMs;
            int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;
            log.info("{} {} [key={}] -> {} ({}ms)", method, path, maskedKey, statusCode, durationMs);
        });
    }

    @Override
    public int getOrder() {
        return -200; // Outermost — logs before everything else including auth
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
