package org.cardanofoundation.cip113.config;

import com.bloxbean.cardano.client.backend.koios.KoiosBackendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class KoiosConfig {

    @Value("${koios.url}")
    private String koiosUrl;

    @Bean
    public KoiosBackendService koiosBackendService() {
        log.info("INIT - Using Koios url: {}", koiosUrl);
        return new KoiosBackendService(koiosUrl);
    }
}
