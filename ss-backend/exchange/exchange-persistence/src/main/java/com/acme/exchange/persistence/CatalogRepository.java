package com.acme.exchange.persistence;

import com.acme.exchange.domain.DeliveryMode;
import com.acme.exchange.domain.SubscriberBinding;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CatalogRepository {

    private final DSLContext dsl;

    public CatalogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<SubscriberBinding> findSubscribers(String tenantId, String flowId, long revision) {
        return dsl.resultQuery("""
                        select subscriber_id, delivery_mode, required, binding_revision
                        from catalog_subscriber
                        where tenant_id = ? and flow_id = ? and revision = ?
                        order by subscriber_id
                        """, tenantId, flowId, revision)
                .fetch(record -> new SubscriberBinding(
                        record.get("subscriber_id", String.class),
                        DeliveryMode.valueOf(record.get("delivery_mode", String.class)),
                        record.get("required", Boolean.class),
                        record.get("binding_revision", Long.class)
                ));
    }

    public Optional<String> findSchema(String tenantId, String flowId, long revision) {
        return dsl.resultQuery("""
                        select schema_json::text
                        from catalog_schema
                        where tenant_id = ? and flow_id = ? and revision = ?
                        """, tenantId, flowId, revision)
                .fetchOptional(0, String.class);
    }
}
