package com.heroku.java.config;

import java.util.Map;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import io.swagger.v3.oas.models.Operation;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OperationCustomizer addSfdcExtensionToOperation() {
        return (Operation operation, HandlerMethod handlerMethod) -> {
            operation.addExtension("x-sfdc", Map.of(
                "heroku", Map.of(
                    "authorization", Map.of(
                        "connectedApp", "GenerateQuoteJobConnectedApp",
                        "permissionSet", "GenerateQuoteJobPermissions"
                    )
                )
            ));
            return operation;
        };
    }    
}
