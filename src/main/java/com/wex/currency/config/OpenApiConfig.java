package com.wex.currency.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI apiInfo() {
        return new OpenAPI().info(new Info()
                .title("Transaction Rate Engine API")
                .version("1.0.0")
                .description("Stores purchase transactions and retrieves them converted to a "
                        + "target currency using the U.S. Treasury Reporting Rates of Exchange API."));
    }
}
