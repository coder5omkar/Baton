package com.acme.exchange.api.service;

import com.acme.exchange.domain.MessageNotFoundException;
import com.acme.exchange.domain.PublicationStatus;
import com.acme.exchange.persistence.PublicationRepository;
import org.springframework.stereotype.Service;

@Service
public class PublicationService {

    private final PublicationRepository publicationRepository;

    public PublicationService(PublicationRepository publicationRepository) {
        this.publicationRepository = publicationRepository;
    }

    public PublicationStatus get(String tenantId, String flowId, String messageId) {
        return publicationRepository.find(tenantId, flowId, messageId)
                .map(this::mapState)
                .orElseThrow(() -> new MessageNotFoundException(messageId));
    }

    private PublicationStatus mapState(PublicationStatus status) {
        String state = switch (status.state()) {
            case "PENDING", "CLAIMED" -> "PUBLISHING";
            case "PUBLISHED" -> "PUBLISHED";
            case "BLOCKED" -> "BLOCKED";
            default -> status.state();
        };
        return new PublicationStatus(
                status.messageId(),
                state,
                status.acceptedAt(),
                status.publishedAt(),
                status.expectedSubscribers());
    }
}
