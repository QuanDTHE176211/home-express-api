package com.homeexpress.home_express_api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP Client Configuration
 * 
 * Centralizes RestTemplate bean configurations for different external API clients.
 * Each RestTemplate is configured with appropriate timeouts based on the service it calls.
 */
@Configuration
public class HttpClientConfig {

    @Value("${http.client.openai.connect-timeout-ms}")
    private Integer openaiConnectTimeout;

    @Value("${http.client.openai.read-timeout-ms}")
    private Integer openaiReadTimeout;

    @Value("${http.client.intake-ai.connect-timeout-ms}")
    private Integer intakeAiConnectTimeout;

    @Value("${http.client.intake-ai.read-timeout-ms}")
    private Integer intakeAiReadTimeout;

    @Value("${http.client.goong.connect-timeout-ms}")
    private Integer goongConnectTimeout;

    @Value("${http.client.goong.read-timeout-ms}")
    private Integer goongReadTimeout;

    /**
     * RestTemplate for OpenAI Vision API calls
     * Configured with longer timeouts for image processing
     */
    @Bean(name = "openaiRestTemplate")
    public RestTemplate openaiRestTemplate() {
        return createRestTemplate(openaiConnectTimeout, openaiReadTimeout);
    }

    /**
     * RestTemplate for Intake AI Parsing Service
     * Configured with moderate timeouts for text processing
     */
    @Bean(name = "intakeAiRestTemplate")
    public RestTemplate intakeAiRestTemplate() {
        return createRestTemplate(intakeAiConnectTimeout, intakeAiReadTimeout);
    }

    /**
     * RestTemplate for Goong Maps API calls
     * Configured with shorter timeouts for quick map queries
     */
    @Bean(name = "goongRestTemplate")
    public RestTemplate goongRestTemplate() {
        return createRestTemplate(goongConnectTimeout, goongReadTimeout);
    }

    /**
     * Helper method to create RestTemplate with specified timeouts
     */
    private RestTemplate createRestTemplate(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}

