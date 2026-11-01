package com.acme.exchange.domain;

public class FlowNotActiveException extends RuntimeException {

    private final String flowId;
    private final String state;

    public FlowNotActiveException(String flowId, String state) {
        super("Flow " + flowId + " is not active (state=" + state + ")");
        this.flowId = flowId;
        this.state = state;
    }

    public String flowId() {
        return flowId;
    }

    public String state() {
        return state;
    }
}
