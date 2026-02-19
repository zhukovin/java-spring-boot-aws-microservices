package com.orderflow.gateway.config;

import com.orderflow.gateway.ratelimit.ApiKeyRateLimitKeyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic Spring Cloud Gateway route definitions.
 *
 * <p>Routes all {@code /orders/**} traffic to the order-service with:
 * <ul>
 *   <li>Redis token-bucket rate limiter (20 req/s sustained, burst 40)</li>
 *   <li>Per-API-key rate limit partitioning via {@link ApiKeyRateLimitKeyResolver}</li>
 * </ul>
 *
 * <p>Rate limiting sits downstream of {@link com.orderflow.gateway.filter.ApiKeyAuthFilter},
 * so only authenticated requests consume rate-limit tokens.
 */
@Configuration
public class GatewayConfig {

    @Value("${app.services.order-service-url:http://order-service:8080}")
    private String orderServiceUrl;

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
                               RedisRateLimiter rateLimiter,
                               KeyResolver keyResolver) {
        return builder.routes()
                .route("order-service", r -> r
                        .path("/orders/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver)))
                        .uri(orderServiceUrl))
                .build();
    }

    /**
     * Token-bucket rate limiter backed by Redis.
     *
     * @param replenishRate  20 tokens added per second (steady-state cap)
     * @param burstCapacity  40 tokens — handles short traffic bursts
     * @param requestedTokens 1 token consumed per request
     *
     * Interview note: Redis ensures the rate limit is consistent across multiple
     * gateway replicas (horizontal scaling), unlike an in-memory limiter that
     * counts per-instance.
     */
    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(20, 40, 1);
    }
}
