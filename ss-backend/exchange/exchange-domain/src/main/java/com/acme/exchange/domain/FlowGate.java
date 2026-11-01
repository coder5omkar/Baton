package com.acme.exchange.domain;

public record FlowGate(
        String tenantId,
        String flowId,
        FlowState state,
        Long activeRevision,
        long generation,
        String catalogChecksum
) {

    public boolean isActive() {
        return state == FlowState.ACTIVE;
    }

    public long requireActiveRevision() {
        if (activeRevision == null) {
            throw new IllegalStateException("Flow " + flowId + " has no active revision");
        }
        return activeRevision;
    }
}
