package com.acme.exchange.persistence;

import com.acme.exchange.domain.PublicationStatus;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class PublicationRepository {

    private final DSLContext dsl;

    public PublicationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<PublicationStatus> find(String tenantId, String flowId, String messageId) {
        return dsl.resultQuery("""
                        select m.message_id,
                               m.accepted_at,
                               o.state,
                               o.published_at,
                               (select count(*)
                                from message_subscriber ms
                                where ms.tenant_id = m.tenant_id
                                  and ms.flow_id = m.flow_id
                                  and ms.message_id = m.message_id) as expected_subscribers
                        from message m
                        left join publish_outbox o
                          on o.tenant_id = m.tenant_id
                         and o.flow_id = m.flow_id
                         and o.message_id = m.message_id
                        where m.tenant_id = ? and m.flow_id = ? and m.message_id = ?
                        """, tenantId, flowId, messageId)
                .fetchOptional()
                .map(this::map);
    }

    private PublicationStatus map(Record record) {
        String state = record.get("state", String.class);
        OffsetDateTime publishedAt = record.get("published_at", OffsetDateTime.class);
        return new PublicationStatus(
                record.get("message_id", String.class),
                state == null ? "ACCEPTED" : state,
                record.get("accepted_at", OffsetDateTime.class).toInstant(),
                publishedAt == null ? null : publishedAt.toInstant(),
                record.get("expected_subscribers", Long.class).intValue()
        );
    }
}
