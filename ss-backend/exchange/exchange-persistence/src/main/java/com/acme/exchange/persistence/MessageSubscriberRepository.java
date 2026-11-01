package com.acme.exchange.persistence;

import com.acme.exchange.domain.AcceptedMessage;
import com.acme.exchange.domain.SubscriberBinding;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MessageSubscriberRepository {

    private final DSLContext dsl;

    public MessageSubscriberRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insertExpected(AcceptedMessage message, List<SubscriberBinding> bindings) {
        if (bindings.isEmpty()) {
            return;
        }
        List<Query> queries = bindings.stream()
                .map(binding -> (Query) dsl.query("""
                                insert into message_subscriber
                                    (tenant_id, flow_id, message_id, subscriber_id, generation,
                                     binding_revision, delivery_mode, dispatch_state)
                                values (?, ?, ?, ?, 0, ?, ?, 'EXPECTED')
                                on conflict do nothing
                                """,
                                message.tenantId(),
                                message.flowId(),
                                message.messageId(),
                                binding.subscriberId(),
                                binding.bindingRevision(),
                                binding.deliveryMode().name()))
                .toList();
        dsl.batch(queries).execute();
    }
}
