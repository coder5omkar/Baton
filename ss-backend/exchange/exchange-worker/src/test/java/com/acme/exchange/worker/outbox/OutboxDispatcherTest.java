package com.acme.exchange.worker.outbox;

import com.acme.exchange.domain.OutboxEntry;
import com.acme.exchange.persistence.OutboxRepository;
import com.acme.exchange.worker.broker.MessageBrokerPort;
import com.acme.exchange.worker.broker.PermanentBrokerException;
import com.acme.exchange.worker.broker.TransientBrokerException;
import com.acme.exchange.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private MessageBrokerPort broker;

    private final WorkerProperties properties = new WorkerProperties();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OutboxDispatcher(outboxRepository, broker, properties);
    }

    @Test
    void marksTheOutboxRowPublishedAfterBrokerAcknowledgement() {
        OutboxEntry entry = entry();
        when(outboxRepository.claimBatch(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(entry));
        when(broker.send(entry)).thenReturn("transport-1");
        when(outboxRepository.markPublished(1L, 5L, "transport-1")).thenReturn(true);

        dispatcher.dispatch();

        verify(outboxRepository).markPublished(1L, 5L, "transport-1");
    }

    @Test
    void reschedulesOnATransientBrokerFailure() {
        OutboxEntry entry = entry();
        when(outboxRepository.claimBatch(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(entry));
        when(broker.send(entry)).thenThrow(new TransientBrokerException("broker unavailable"));

        dispatcher.dispatch();

        verify(outboxRepository).reschedule(
                eq(1L), eq(5L), any(Duration.class), contains("broker unavailable"));
    }

    @Test
    void blocksOnAPermanentBrokerFailure() {
        OutboxEntry entry = entry();
        when(outboxRepository.claimBatch(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(List.of(entry));
        when(broker.send(entry)).thenThrow(new PermanentBrokerException("topic does not exist"));

        dispatcher.dispatch();

        verify(outboxRepository).block(1L, 5L, "topic does not exist");
    }

    @Test
    void doesNotCallTheBrokerWhenTheClaimFails() {
        when(outboxRepository.claimBatch(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        dispatcher.dispatch();

        verifyNoInteractions(broker);
    }

    private OutboxEntry entry() {
        return new OutboxEntry(
                1L,
                "tenant-dev",
                "orders",
                "msg-1",
                1L,
                mapper.createObjectNode().put("id", "msg-1"),
                1,
                5L,
                null);
    }
}
