package com.orderflow.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OrderFlow Order Service API")
                        .description("""
                                Handles order creation with:
                                - Idempotency via DynamoDB TransactWriteItems + attribute_not_exists(PK)
                                - Transactional outbox pattern for reliable Kafka delivery
                                """)
                        .version("1.0.0"));
    }

    @Bean
    public OperationCustomizer idempotencyKeyCustomizer() {
        return (operation, handlerMethod) -> {
            if ("createOrder".equals(handlerMethod.getMethod().getName())) {
                operation.addParametersItem(new HeaderParameter()
                        .name("Idempotency-Key")
                        .description("UUID for idempotent request deduplication. " +
                                "Duplicate requests with the same key return the cached response (24 h TTL).")
                        .required(true)
                        .example("550e8400-e29b-41d4-a716-446655440000"));
            }
            return operation;
        };
    }
}
