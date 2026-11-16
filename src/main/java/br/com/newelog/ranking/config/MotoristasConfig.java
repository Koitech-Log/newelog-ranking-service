package br.com.newelog.ranking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MotoristasConfig {
    @Bean
    RestClient motoristasRestClient(@Value("${motoristas.url}") String url,
            @Value("${motoristas.connect-timeout-ms}") int connectMs,
            @Value("${motoristas.read-timeout-ms}") int readMs) {
        if (connectMs <= 0 || readMs <= 0)
            throw new IllegalStateException("Timeouts do serviço de motoristas devem ser positivos.");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
