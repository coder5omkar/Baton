package com.acme.exchange.persistence;

import com.acme.exchange.domain.FlowGate;
import com.acme.exchange.domain.FlowState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class FlowGateRepository {

    private final DSLContext dsl;

    public FlowGateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Shared admission lock. Activation takes an exclusive lock on the same row,
     * so it waits for in-flight acceptance transactions to commit before flipping
     * the active revision.
     */
    public Optional<FlowGate> lockForAdmission(String tenantId, String flowId) {
        return dsl.resultQuery("""
                        select tenant_id, flow_id, state, active_revision, generation, catalog_checksum
                        from flow_gate
                        where tenant_id = ? and flow_id = ?
                        for share
                        """, tenantId, flowId)
                .fetchOptional()
                .map(this::map);
    }

    public Optional<FlowGate> lockForActivation(String tenantId, String flowId) {
        return dsl.resultQuery("""
                        select tenant_id, flow_id, state, active_revision, generation, catalog_checksum
                        from flow_gate
                        where tenant_id = ? and flow_id = ?
                        for update
                        """, tenantId, flowId)
                .fetchOptional()
                .map(this::map);
    }

    public Optional<FlowGate> find(String tenantId, String flowId) {
        return dsl.resultQuery("""
                        select tenant_id, flow_id, state, active_revision, generation, catalog_checksum
                        from flow_gate
                        where tenant_id = ? and flow_id = ?
                        """, tenantId, flowId)
                .fetchOptional()
                .map(this::map);
    }

    private FlowGate map(Record record) {
        return new FlowGate(
                record.get("tenant_id", String.class),
                record.get("flow_id", String.class),
                FlowState.valueOf(record.get("state", String.class)),
                record.get("active_revision", Long.class),
                record.get("generation", Long.class),
                record.get("catalog_checksum", String.class)
        );
    }
}
