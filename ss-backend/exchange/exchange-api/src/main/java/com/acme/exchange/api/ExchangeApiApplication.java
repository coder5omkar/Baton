package com.acme.exchange.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.acme")
@ConfigurationPropertiesScan
public class ExchangeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeApiApplication.class, args);
    }
}
