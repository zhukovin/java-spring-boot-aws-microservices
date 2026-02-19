package com.orderflow.payment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class Resilience4jConfig {

    /**
     * Logs circuit breaker state transitions so operators can see when the
     * payment gateway circuit opens/closes.
     */
    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerEventConsumer() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
                event.getAddedEntry().getEventPublisher()
                        .onStateTransition(e -> log.warn(
                                "CircuitBreaker '{}': {} -> {}",
                                e.getCircuitBreakerName(),
                                e.getStateTransition().getFromState(),
                                e.getStateTransition().getToState()));
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {}

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) {}
        };
    }
}
