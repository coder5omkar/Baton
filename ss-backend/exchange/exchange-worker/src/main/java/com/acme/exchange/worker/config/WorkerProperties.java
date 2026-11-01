package com.acme.exchange.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "exchange.worker")
public class WorkerProperties {

    private final Outbox outbox = new Outbox();

    public Outbox getOutbox() {
        return outbox;
    }

    public static class Outbox {

        private int claimBatch = 50;
        private Duration lease = Duration.ofSeconds(30);
        private Duration maxBackoff = Duration.ofMinutes(5);

        public int getClaimBatch() {
            return claimBatch;
        }

        public void setClaimBatch(int claimBatch) {
            this.claimBatch = claimBatch;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }
    }
}
