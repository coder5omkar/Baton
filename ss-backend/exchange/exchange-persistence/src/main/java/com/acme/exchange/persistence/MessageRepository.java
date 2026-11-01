package com.acme.exchange.persistence;

import com.acme.exchange.domain.AcceptedMessage;
import com.acme.exchange.domain.FlowGate;
import com.acme.exchange.domain.PublishCommand;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class MessageRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public MessageRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public Optional<AcceptedMessage> find(String tenantId, String flowId, String messageId) {
        return dsl.resultQuery("""
                        select tenant_id, flow_id, message_id, revision, content_hash, envelope,
                               route_revision, accepted_at, expires_at
                        from message
                        where tenant_id = ? and flow_id = ? and message_id = ?
                        """, tenantId, flowId, messageId)
                .fetchOptional()
                .map(this::map);
    }

    /**
     * Inserts the accepted message. If a concurrent publisher won the unique
     * key, returns empty and the caller resolves the existing row.
     */
    public Optional<AcceptedMessage> insertIfAbsent(
            PublishCommand command,
            FlowGate gate,
            String messageId,
            String contentHash,
            Duration retention
    ) {
        long revision = gate.requireActiveRevision();
        return dsl.resultQuery("""
                        insert into message
                            (tenant_id, flow_id, message_id, revision, content_hash, envelope,
                             headers, route_revision, accepted_at, expires_at)
                        values
                            (?, ?, ?, ?, ?, ?::jsonb, '{}'::jsonb, ?, now(), now() + (? * interval '1 second'))
                        on conflict (tenant_id, flow_id, message_id) do nothing
                        returning tenant_id, flow_id, message_id, revision, content_hash, envelope,
                                  route_revision, accepted_at, expires_at
                        """,
                        command.tenantId(),
                        command.flowId(),
                        messageId,
                        revision,
                        contentHash,
                        JSONB.valueOf(command.envelope().toString()),
                        revision,
                        retention.toSeconds())
                .fetchOptional()
                .map(this::map);
    }

    private AcceptedMessage map(Record record) {
        return new AcceptedMessage(
                record.get("tenant_id", String.class),
                record.get("flow_id", String.class),
                record.get("message_id", String.class),
                record.get("revision", Long.class),
                record.get("content_hash", String.class),
                readJson(record.get("envelope", JSONB.class)),
                record.get("route_revision", Long.class),
                record.get("accepted_at", OffsetDateTime.class).toInstant(),
                record.get("expires_at", OffsetDateTime.class).toInstant()
        );
    }

    private JsonNode readJson(JSONB jsonb) {
        try {
            return objectMapper.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored message envelope is not valid JSON", e);
        }
    }
}
