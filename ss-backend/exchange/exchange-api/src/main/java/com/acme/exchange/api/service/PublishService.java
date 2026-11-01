package com.acme.exchange.api.service;

import com.acme.exchange.api.config.ExchangeProperties;
import com.acme.exchange.domain.Acceptance;
import com.acme.exchange.domain.AcceptedMessage;
import com.acme.exchange.domain.EnvelopeValidator;
import com.acme.exchange.domain.FlowGate;
import com.acme.exchange.domain.FlowNotActiveException;
import com.acme.exchange.domain.MessageIdConflictException;
import com.acme.exchange.domain.PublishCommand;
import com.acme.exchange.domain.StaleRevisionException;
import com.acme.exchange.domain.SubscriberBinding;
import com.acme.exchange.domain.ValidatedEnvelope;
import com.acme.exchange.persistence.CatalogRepository;
import com.acme.exchange.persistence.FlowGateRepository;
import com.acme.exchange.persistence.MessageRepository;
import com.acme.exchange.persistence.MessageSubscriberRepository;
import com.acme.exchange.persistence.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PublishService {

    private final FlowGateRepository flowGateRepository;
    private final MessageRepository messageRepository;
    private final MessageSubscriberRepository messageSubscriberRepository;
    private final OutboxRepository outboxRepository;
    private final CatalogRepository catalogRepository;
    private final EnvelopeValidator envelopeValidator;
    private final ExchangeProperties properties;

    public PublishService(
            FlowGateRepository flowGateRepository,
            MessageRepository messageRepository,
            MessageSubscriberRepository messageSubscriberRepository,
            OutboxRepository outboxRepository,
            CatalogRepository catalogRepository,
            EnvelopeValidator envelopeValidator,
            ExchangeProperties properties
    ) {
        this.flowGateRepository = flowGateRepository;
        this.messageRepository = messageRepository;
        this.messageSubscriberRepository = messageSubscriberRepository;
        this.outboxRepository = outboxRepository;
        this.catalogRepository = catalogRepository;
        this.envelopeValidator = envelopeValidator;
        this.properties = properties;
    }

    /**
     * Durable acceptance. The message, expected subscriber snapshot, and outbox
     * row commit in one local transaction. No broker I/O happens here.
     */
    @Transactional
    public Acceptance accept(PublishCommand command) {
        ValidatedEnvelope validated = envelopeValidator.validate(
                command.envelope(),
                properties.getMessage().getMaxInlineBytes());

        FlowGate gate = flowGateRepository
                .lockForAdmission(command.tenantId(), command.flowId())
                .orElseThrow(() -> new FlowNotActiveException(command.flowId(), "NOT_CONFIGURED"));

        if (!gate.isActive()) {
            throw new FlowNotActiveException(command.flowId(), gate.state().name());
        }
        if (command.revision() != null && command.revision().longValue() != gate.requireActiveRevision()) {
            throw new StaleRevisionException(gate.requireActiveRevision());
        }

        Optional<AcceptedMessage> existing = messageRepository.find(
                command.tenantId(), command.flowId(), validated.messageId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), validated.contentHash());
        }

        Optional<AcceptedMessage> inserted = messageRepository.insertIfAbsent(
                command,
                gate,
                validated.messageId(),
                validated.contentHash(),
                properties.getMessage().getRetention());

        if (inserted.isEmpty()) {
            AcceptedMessage raced = messageRepository.find(
                            command.tenantId(), command.flowId(), validated.messageId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Message disappeared after a unique-key conflict: " + validated.messageId()));
            return replayOrConflict(raced, validated.contentHash());
        }

        AcceptedMessage accepted = inserted.get();
        List<SubscriberBinding> subscribers = catalogRepository.findSubscribers(
                command.tenantId(), command.flowId(), accepted.revision());
        messageSubscriberRepository.insertExpected(accepted, subscribers);
        outboxRepository.enqueue(accepted);

        return new Acceptance(accepted.messageId(), accepted.acceptedAt(), false);
    }

    private Acceptance replayOrConflict(AcceptedMessage existing, String contentHash) {
        if (!existing.contentHash().equals(contentHash)) {
            throw new MessageIdConflictException(existing.messageId(), existing.acceptedAt());
        }
        return new Acceptance(existing.messageId(), existing.acceptedAt(), true);
    }
}
