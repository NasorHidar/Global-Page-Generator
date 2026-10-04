package com.globalpagegenerator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures the shared Spring {@link RestClient} used by {@code ExecutionService}
 * to call third-party endpoints.
 *
 * <p>Timeout values are read from {@code application.yml} under
 * {@code app.rest-client.*} so they can be overridden per environment
 * without recompilation.
 *
 * <p><b>Why {@code RestClient.Builder} not {@code RestClient}?</b>
 * Injecting the builder lets {@code ExecutionService} stamp per-call
 * headers (e.g., a tracing ID) onto a fresh client instance without
 * sharing mutable state across threads.
 */
@Configuration
public class RestClientConfig {

    @Value("${app.rest-client.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.rest-client.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json");
    }
}
