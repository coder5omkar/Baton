package com.acme.exchange.api.service;

import com.acme.exchange.api.config.ExchangeProperties;
import com.acme.exchange.domain.AcceptedMessage;
import com.acme.exchange.domain.Acceptance;
import com.acme.exchange.domain.DeliveryMode;
import com.acme.exchange.domain.EnvelopeValidator;
import com.acme.exchange.domain.FlowGate;
import com.acme.exchange.domain.FlowNotActiveException;
import com.acme.exchange.domain.FlowSchemaValidator;
import com.acme.exchange.domain.FlowState;
import com.acme.exchange.domain.MessageIdConflictException;
import com.acme.exchange.domain.PublishCommand;
import com.acme.exchange.domain.SchemaValidationException;
import com.acme.exchange.domain.StaleRevisionException;
import com.acme.exchange.domain.SubscriberBinding;
import com.acme.exchange.domain.ValidatedEnvelope;
import com.acme.exchange.persistence.CatalogRepository;
import com.acme.exchange.persistence.FlowGateRepository;
import com.acme.exchange.persistence.MessageRepository;
import com.acme.exchange.persistence.MessageSubscriberRepository;
import com.acme.exchange.persistence.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock
    private FlowGateRepository flowGateRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageSubscriberRepository messageSubscriberRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private CatalogRepository catalogRepository;
    @Mock
    private EnvelopeValidator envelopeValidator;
    @Mock
    private FlowSchemaValidator flowSchemaValidator;

    private final ExchangeProperties properties = new ExchangeProperties();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private PublishService service;

    @BeforeEach
    void setUp() {
        service = new PublishService(
                flowGateRepository,
                messageRepository,
                messageSubscriberRepository,
                outboxRepository,
                catalogRepository,
                envelopeValidator,
                flowSchemaValidator,
                properties);
    }

    @Test
    void acceptsANewMessageAndWritesTheExpectedSetAndOutbox() {
        JsonNode envelope = envelope();
        FlowGate gate = activeGate(1L);
        AcceptedMessage accepted = accepted("hash-1");
        SubscriberBinding binding = new SubscriberBinding("billing", DeliveryMode.WEBHOOK, true, 1L);

        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(gate));
        when(messageRepository.find("tenant-dev", "orders", "msg-1"))
                .thenReturn(Optional.empty());
        when(catalogRepository.findSchema("tenant-dev", "orders", 1L))
                .thenReturn(Optional.of("{\"type\":\"object\"}"));
        when(messageRepository.insertIfAbsent(any(), any(), eq("msg-1"), eq("hash-1"), any()))
                .thenReturn(Optional.of(accepted));
        when(catalogRepository.findSubscribers("tenant-dev", "orders", 1L))
                .thenReturn(List.of(binding));

        Acceptance result = service.accept(new PublishCommand("tenant-dev", "orders", 1L, envelope));

        assertThat(result.messageId()).isEqualTo("msg-1");
        assertThat(result.replayed()).isFalse();
        verify(flowSchemaValidator).validate("{\"type\":\"object\"}", envelope);
        verify(messageSubscriberRepository).insertExpected(accepted, List.of(binding));
        verify(outboxRepository).enqueue(accepted);
    }

    @Test
    void rejectsAnEnvelopeThatDoesNotMatchTheFlowSchema() {
        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(activeGate(1L)));
        when(messageRepository.find("tenant-dev", "orders", "msg-1"))
                .thenReturn(Optional.empty());
        when(catalogRepository.findSchema("tenant-dev", "orders", 1L))
                .thenReturn(Optional.of("{\"type\":\"object\"}"));
        doThrow(new SchemaValidationException("bad envelope", List.of("data.orderId is required")))
                .when(flowSchemaValidator).validate(any(), any());

        assertThatThrownBy(() -> service.accept(
                new PublishCommand("tenant-dev", "orders", 1L, envelope())))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("bad envelope");

        verify(messageRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void rejectsWhenNoSchemaExistsForTheActiveRevision() {
        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(activeGate(1L)));
        when(messageRepository.find("tenant-dev", "orders", "msg-1"))
                .thenReturn(Optional.empty());
        when(catalogRepository.findSchema("tenant-dev", "orders", 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(
                new PublishCommand("tenant-dev", "orders", 1L, envelope())))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("No schema configured");

        verifyNoInteractions(flowSchemaValidator);
        verify(messageRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void returnsTheOriginalAcceptanceForAnIdenticalRetry() {
        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(activeGate(1L)));
        when(messageRepository.find("tenant-dev", "orders", "msg-1"))
                .thenReturn(Optional.of(accepted("hash-1")));

        Acceptance result = service.accept(new PublishCommand("tenant-dev", "orders", 1L, envelope()));

        assertThat(result.replayed()).isTrue();
        verifyNoInteractions(outboxRepository);
        verify(messageRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsTheSameIdWithDifferentContent() {
        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(activeGate(1L)));
        when(messageRepository.find("tenant-dev", "orders", "msg-1"))
                .thenReturn(Optional.of(accepted("different-hash")));

        assertThatThrownBy(() -> service.accept(
                new PublishCommand("tenant-dev", "orders", 1L, envelope())))
                .isInstanceOf(MessageIdConflictException.class);
    }

    @Test
    void rejectsAPublishAgainstASuspendedFlow() {
        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(new FlowGate(
                        "tenant-dev", "orders", FlowState.SUSPENDED, 1L, 1L, "checksum")));

        assertThatThrownBy(() -> service.accept(
                new PublishCommand("tenant-dev", "orders", 1L, envelope())))
                .isInstanceOf(FlowNotActiveException.class);
    }

    @Test
    void rejectsARevisionThatIsNoLongerActive() {
        when(envelopeValidator.validate(any(), anyInt()))
                .thenReturn(new ValidatedEnvelope("msg-1", "hash-1", 100));
        when(flowGateRepository.lockForAdmission("tenant-dev", "orders"))
                .thenReturn(Optional.of(activeGate(2L)));

        assertThatThrownBy(() -> service.accept(
                new PublishCommand("tenant-dev", "orders", 1L, envelope())))
                .isInstanceOf(StaleRevisionException.class);
    }

    private FlowGate activeGate(long revision) {
        return new FlowGate("tenant-dev", "orders", FlowState.ACTIVE, revision, 1L, "checksum");
    }

    private AcceptedMessage accepted(String contentHash) {
        return new AcceptedMessage(
                "tenant-dev",
                "orders",
                "msg-1",
                1L,
                contentHash,
                envelope(),
                1L,
                Instant.parse("2026-11-01T10:15:30Z"),
                Instant.parse("2026-12-01T10:15:30Z"));
    }

    private JsonNode envelope() {
        return mapper.readTree("""
                {
                  "specversion": "1.0",
                  "id": "msg-1",
                  "source": "test",
                  "type": "order.created",
                  "data": {"orderId": "123"}
                }
                """);
    }
}
