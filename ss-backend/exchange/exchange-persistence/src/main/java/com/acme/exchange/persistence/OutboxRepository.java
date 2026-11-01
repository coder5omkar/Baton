package com.acme.exchange.persistence;

import com.acme.exchange.domain.AcceptedMessage;
import com.acme.exchange.domain.OutboxEntry;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

@Repository
public class OutboxRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public OutboxRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public void enqueue(AcceptedMessage message) {
        dsl.execute("""
                        insert into publish_outbox
                            (tenant_id, flow_id, message_id, state, next_attempt_at)
                        values (?, ?, ?, 'PENDING', now())
                        on conflict (tenant_id, flow_id, message_id) do nothing
                        """,
                message.tenantId(),
                message.flowId(),
                message.messageId());
    }

    /**
     * Claims a bounded batch with row locks and a fence. The claim transaction
     * commits before any broker I/O happens.
     */
    public List<OutboxEntry> claimBatch(String owner, int batchSize, Duration lease) {
        return dsl.resultQuery("""
                        with due as (
                            select id
                            from publish_outbox
                            where (state = 'PENDING' and next_attempt_at <= now())
                               or (state = 'CLAIMED' and lease_expires_at < now())
                            order by next_attempt_at
                            limit ?
                            for update skip locked
                        ),
                        claimed as (
                            update publish_outbox o
                            set state = 'CLAIMED',
                                lease_owner = ?,
                                lease_expires_at = now() + (? * interval '1 second'),
                                attempt = o.attempt + 1,
                                fence = o.fence + 1
                            from due
                            where o.id = due.id
                            returning o.id, o.tenant_id, o.flow_id, o.message_id,
                                      o.attempt, o.fence, o.traceparent
                        )
                        select c.id, c.tenant_id, c.flow_id, c.message_id,
                               c.attempt, c.fence, c.traceparent,
                               m.revision, m.envelope
                        from claimed c
                        join message m
                          on m.tenant_id = c.tenant_id
                         and m.flow_id = c.flow_id
                         and m.message_id = c.message_id
                        """,
                        batchSize,
                        owner,
                        lease.toSeconds())
                .fetch(this::map);
    }

    public boolean markPublished(long id, long fence, String transportId) {
        return dsl.execute("""
                        update publish_outbox
                        set state = 'PUBLISHED',
                            published_at = now(),
                            transport_id = ?,
                            lease_owner = null,
                            lease_expires_at = null,
                            last_error = null
                        where id = ? and fence = ? and state = 'CLAIMED'
                        """, transportId, id, fence) == 1;
    }

    public boolean reschedule(long id, long fence, Duration delay, String error) {
        return dsl.execute("""
                        update publish_outbox
                        set state = 'PENDING',
                            next_attempt_at = now() + (? * interval '1 millisecond'),
                            lease_owner = null,
                            lease_expires_at = null,
                            last_error = ?
                        where id = ? and fence = ? and state = 'CLAIMED'
                        """, delay.toMillis(), error, id, fence) == 1;
    }

    public boolean block(long id, long fence, String error) {
        return dsl.execute("""
                        update publish_outbox
                        set state = 'BLOCKED',
                            lease_owner = null,
                            lease_expires_at = null,
                            last_error = ?
                        where id = ? and fence = ? and state = 'CLAIMED'
                        """, error, id, fence) == 1;
    }

    public long countPending() {
        Long count = dsl.resultQuery("""
                        select count(*)
                        from publish_outbox
                        where state = 'PENDING'
                        """)
                .fetchOne(0, Long.class);
        return count == null ? 0 : count;
    }

    private OutboxEntry map(Record record) {
        return new OutboxEntry(
                record.get("id", Long.class),
                record.get("tenant_id", String.class),
                record.get("flow_id", String.class),
                record.get("message_id", String.class),
                record.get("revision", Long.class),
                readJson(record.get("envelope", JSONB.class)),
                record.get("attempt", Integer.class),
                record.get("fence", Long.class),
                record.get("traceparent", String.class)
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
