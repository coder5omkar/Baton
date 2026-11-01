package com.acme.exchange.api.web;

import com.acme.exchange.api.security.TenantContext;
import com.acme.exchange.api.service.PublicationService;
import com.acme.exchange.domain.PublicationStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/flows/{flowId}/publications")
public class PublicationController {

    private final PublicationService publicationService;

    public PublicationController(PublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @GetMapping("/{messageId}")
    public PublicationStatus get(@PathVariable String flowId, @PathVariable String messageId) {
        return publicationService.get(TenantContext.require(), flowId, messageId);
    }
}
