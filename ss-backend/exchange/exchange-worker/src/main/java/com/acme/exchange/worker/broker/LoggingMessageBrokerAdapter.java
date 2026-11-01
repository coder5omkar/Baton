package com.acme.exchange.worker.broker;

import com.acme.exchange.domain.OutboxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Development adapter. It proves the outbox state machine without requiring a
 * Service Bus namespace. Replace it with the Service Bus adapter in deployment.
 */
@Component
@ConditionalOnProperty(
        name = "exchange.broker.adapter",
        havingValue = "logging",
        matchIfMissing = true)
public class LoggingMessageBrokerAdapter implements MessageBrokerPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageBrokerAdapter.class);

    @Override
    public String send(OutboxEntry entry) {
        log.info("Publishing messageId={} tenantId={} flowId={} revision={} attempt={} (logging adapter)",
                entry.messageId(), entry.tenantId(), entry.flowId(), entry.revision(), entry.attempt());
        return "logging-" + UUID.randomUUID();
    }
}
