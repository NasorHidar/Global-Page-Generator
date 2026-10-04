package com.globalpagegenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Global Page Generator backend.
 *
 * <p>Activates Spring Boot's component scanning rooted at this package and
 * its sub-packages, including:
 * <ul>
 *   <li>{@code config/}      — Jackson, RestClient, Cache, CORS, ...</li>
 *   <li>{@code persistence/} — JPA entities, repositories, converters</li>
 *   <li>{@code service/}     — ExecutionService, LayoutService</li>
 *   <li>{@code web/}         — REST controllers and exception advice</li>
 *   <li>{@code security/}    — bearer-token auth filter, principal</li>
 *   <li>{@code validation/}  — Bean Validation annotations</li>
 * </ul>
 *
 * <p>{@code @SpringBootApplication} is equivalent to combining
 * {@code @Configuration}, {@code @EnableAutoConfiguration}, and
 * {@code @ComponentScan}. Caching is enabled separately in
 * {@link com.globalpagegenerator.config.CacheConfig} via {@code @EnableCaching}.
 */
@SpringBootApplication
public class GlobalPageGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlobalPageGeneratorApplication.class, args);
    }
}