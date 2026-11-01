package com.acme.exchange.api.config;

import com.acme.exchange.domain.CloudEventsEnvelopeValidator;
import com.acme.exchange.domain.EnvelopeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidatorConfig {

    @Bean
    public EnvelopeValidator envelopeValidator() {
        return new CloudEventsEnvelopeValidator();
    }
}
