package com.acme.exchange.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "exchange")
public class ExchangeProperties {

    private final Message message = new Message();
    private final Security security = new Security();

    public Message getMessage() {
        return message;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Message {

        private int maxInlineBytes = 131_072;
        private Duration retention = Duration.ofDays(30);

        public int getMaxInlineBytes() {
            return maxInlineBytes;
        }

        public void setMaxInlineBytes(int maxInlineBytes) {
            this.maxInlineBytes = maxInlineBytes;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }

    public static class Security {

        private String issuer = "https://login.microsoftonline.com/common/v2.0";
        private String audience = "api://exchange";

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }
    }
}
