package com.acme.exchange.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.acme")
@ConfigurationPropertiesScan
@EnableScheduling
public class ExchangeWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeWorkerApplication.class, args);
    }
}
