package com.acme.exchange.api.web;

import com.acme.exchange.api.security.TenantContext;
import com.acme.exchange.api.service.PublishService;
import com.acme.exchange.api.web.dto.AcceptanceResponse;
import com.acme.exchange.domain.Acceptance;
import com.acme.exchange.domain.PublishCommand;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/flows/{flowId}/messages")
public class PublishController {

    private final PublishService publishService;

    public PublishController(PublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping
    public ResponseEntity<AcceptanceResponse> publish(
            @PathVariable String flowId,
            @RequestHeader(name = "X-Flow-Revision", required = false) Long revision,
            @RequestBody JsonNode envelope
    ) {
        Acceptance acceptance = publishService.accept(
                new PublishCommand(TenantContext.require(), flowId, revision, envelope));

        return ResponseEntity.accepted()
                .location(UriComponentsBuilder
                        .fromPath("/v1/flows/{flowId}/publications/{messageId}")
                        .buildAndExpand(flowId, acceptance.messageId())
                        .encode()
                        .toUri())
                .header("X-Acceptance-Time", acceptance.acceptedAt().toString())
                .body(new AcceptanceResponse(acceptance.messageId(), "ACCEPTED", acceptance.replayed()));
    }
}
