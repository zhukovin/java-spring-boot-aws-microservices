package com.orderflow.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Rate-limit key resolver that partitions the Redis token bucket by API key.
 *
 * <p>Each unique API key gets its own independent bucket (20 req/s, burst 40).
 * This prevents one noisy client from exhausting the limit for all others.
 *
 * <p>Falls back to remote IP when the API key is absent — useful for the brief
 * window between the auth filter rejection and the rate-limiter evaluation.
 *
 * <p>Implements {@link KeyResolver} so Spring Cloud Gateway can auto-wire it
 * into {@link com.orderflow.gateway.config.GatewayConfig#routes}.
 */
@Component
public class ApiKeyRateLimitKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return Mono.just(apiKey.trim());
        }
        // Fallback: rate-limit by IP (covers the rejected-before-auth case)
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String ip = (remote != null)
                ? remote.getAddress().getHostAddress()
                : "unknown";
        return Mono.just(ip);
    }
}
