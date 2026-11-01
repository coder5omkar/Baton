package com.acme.exchange.api.web;

import com.acme.exchange.api.security.SubscriberContext;
import com.acme.exchange.api.security.TenantContext;
import com.acme.exchange.api.service.DeliveryService;
import com.acme.exchange.api.web.dto.LeaseRequest;
import com.acme.exchange.api.web.dto.RenewRequest;
import com.acme.exchange.api.web.dto.SettleRequest;
import com.acme.exchange.domain.DeliveryLease;
import com.acme.exchange.domain.DeliveryRenewal;
import com.acme.exchange.domain.DeliverySettlement;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/deliveries")
public class DeliveryController {

    private static final String LEASE_TOKEN_HEADER = "X-Lease-Token";

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostMapping("/lease")
    public ResponseEntity<DeliveryLease> lease(
            @RequestBody(required = false) LeaseRequest request
    ) {
        return deliveryService.lease(
                        TenantContext.require(),
                        SubscriberContext.require(),
                        request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{deliveryId}/renew")
    public DeliveryRenewal renew(
            @PathVariable String deliveryId,
            @RequestHeader(name = LEASE_TOKEN_HEADER, required = false) String leaseToken,
            @RequestBody(required = false) RenewRequest request
    ) {
        return deliveryService.renew(
                TenantContext.require(),
                SubscriberContext.require(),
                parseDeliveryId(deliveryId),
                leaseToken,
                request);
    }

    @PostMapping("/{deliveryId}/settle")
    public DeliverySettlement settle(
            @PathVariable String deliveryId,
            @RequestHeader(name = LEASE_TOKEN_HEADER, required = false) String leaseToken,
            @RequestBody(required = false) SettleRequest request
    ) {
        return deliveryService.settle(
                TenantContext.require(),
                SubscriberContext.require(),
                parseDeliveryId(deliveryId),
                leaseToken,
                request);
    }

    private static UUID parseDeliveryId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("deliveryId must be a UUID");
        }
    }
}
