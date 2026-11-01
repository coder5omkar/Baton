package com.acme.exchange.worker.broker;

import com.acme.exchange.domain.OutboxEntry;

public interface MessageBrokerPort {

    /**
     * Publishes one message and returns the broker transport id.
     *
     * @throws TransientBrokerException when the send may succeed on retry
     * @throws PermanentBrokerException when the send cannot succeed without operator action
     */
    String send(OutboxEntry entry);
}
