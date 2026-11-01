package com.acme.exchange.worker.outbox;

import com.acme.exchange.domain.OutboxEntry;
import com.acme.exchange.persistence.OutboxRepository;
import com.acme.exchange.worker.broker.MessageBrokerPort;
import com.acme.exchange.worker.broker.PermanentBrokerException;
import com.acme.exchange.worker.broker.TransientBrokerException;
import com.acme.exchange.worker.config.WorkerProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepository;
    private final MessageBrokerPort broker;
    private final WorkerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String ownerId = ownerId();

    public OutboxDispatcher(
            OutboxRepository outboxRepository,
            MessageBrokerPort broker,
            WorkerProperties properties
    ) {
        this.outboxRepository = outboxRepository;
        this.broker = broker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${exchange.worker.outbox.poll-interval-ms:1000}")
    public void dispatch() {
        if (!running.get()) {
            return;
        }

        List<OutboxEntry> batch;
        try {
            batch = outboxRepository.claimBatch(
                    ownerId,
                    properties.getOutbox().getClaimBatch(),
                    properties.getOutbox().getLease());
        } catch (Exception exception) {
            log.error("Outbox claim failed; will retry on the next poll", exception);
            return;
        }

        for (OutboxEntry entry : batch) {
            dispatchOne(entry);
        }
    }

    private void dispatchOne(OutboxEntry entry) {
        try {
            String transportId = broker.send(entry);
            if (!outboxRepository.markPublished(entry.id(), entry.fence(), transportId)) {
                log.warn("Lost the outbox fence for id={} messageId={} before publish confirmation",
                        entry.id(), entry.messageId());
            }
        } catch (TransientBrokerException exception) {
            Duration delay = backoff(entry.attempt());
            outboxRepository.reschedule(entry.id(), entry.fence(), delay, exception.getMessage());
            log.warn("Transient publish failure for messageId={}; retrying in {}",
                    entry.messageId(), delay, exception);
        } catch (PermanentBrokerException exception) {
            outboxRepository.block(entry.id(), entry.fence(), exception.getMessage());
            log.error("Permanent publish failure for messageId={}; outbox row blocked",
                    entry.messageId(), exception);
        } catch (Exception exception) {
            Duration delay = backoff(entry.attempt());
            outboxRepository.reschedule(entry.id(), entry.fence(), delay, exception.getMessage());
            log.error("Unexpected publish failure for messageId={}; retrying in {}",
                    entry.messageId(), delay, exception);
        }
    }

    private Duration backoff(int attempt) {
        long base = 1_000L << Math.min(attempt, 10);
        long capped = Math.min(base, properties.getOutbox().getMaxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, capped / 4));
        return Duration.ofMillis(capped + jitter);
    }

    @PreDestroy
    void shutdown() {
        running.set(false);
    }

    private static String ownerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (UnknownHostException exception) {
            return "worker-" + UUID.randomUUID();
        }
    }
}
