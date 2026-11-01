package com.acme.exchange.api.service;

import com.acme.exchange.api.config.ExchangeProperties;
import com.acme.exchange.api.web.dto.LeaseRequest;
import com.acme.exchange.api.web.dto.RenewRequest;
import com.acme.exchange.api.web.dto.SettleRequest;
import com.acme.exchange.domain.DeliveryClaim;
import com.acme.exchange.domain.DeliveryLease;
import com.acme.exchange.domain.DeliveryOutcome;
import com.acme.exchange.domain.DeliveryRenewal;
import com.acme.exchange.domain.DeliverySettlement;
import com.acme.exchange.domain.LeaseLostException;
import com.acme.exchange.persistence.DeliveryRepository;
import com.acme.platform.hash.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryRepository deliveryRepository;
    private final ExchangeProperties properties;

    public DeliveryService(DeliveryRepository deliveryRepository, ExchangeProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
    }

    @Transactional
    public Optional<DeliveryLease> lease(
            String tenantId,
            String subscriberId,
            LeaseRequest request
    ) {
        validateMaxMessages(request == null ? null : request.maxMessages());
        int leaseSeconds = leaseSeconds(request == null ? null : request.leaseSeconds());

        String token = newToken();
        String tokenHash = Hashing.sha256Hex(token);

        return deliveryRepository.claimPull(
                        tenantId,
                        subscriberId,
                        "poll:" + subscriberId,
                        leaseSeconds,
                        tokenHash)
                .map(claim -> toLease(claim, token));
    }

    @Transactional
    public DeliveryRenewal renew(
            String tenantId,
            String subscriberId,
            UUID deliveryId,
            String leaseToken,
            RenewRequest request
    ) {
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new LeaseLostException(deliveryId);
        }
        int leaseSeconds = leaseSeconds(request == null ? null : request.leaseSeconds());
        return deliveryRepository.renew(
                        deliveryId,
                        tenantId,
                        subscriberId,
                        Hashing.sha256Hex(leaseToken),
                        leaseSeconds,
                        maxLeaseBudgetSeconds())
                .orElseThrow(() -> new LeaseLostException(deliveryId));
    }

    @Transactional
    public DeliverySettlement settle(
            String tenantId,
            String subscriberId,
            UUID deliveryId,
            String leaseToken,
            SettleRequest request
    ) {
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new LeaseLostException(deliveryId);
        }
        DeliveryOutcome outcome = parseOutcome(request == null ? null : request.outcome());
        return deliveryRepository.settle(
                deliveryId,
                tenantId,
                subscriberId,
                Hashing.sha256Hex(leaseToken),
                outcome,
                request == null ? null : request.errorCode());
    }

    private DeliveryLease toLease(DeliveryClaim claim, String token) {
        return new DeliveryLease(
                claim.deliveryId(),
                token,
                claim.leaseExpiresAt(),
                claim.attempt(),
                claim.envelope());
    }

    private void validateMaxMessages(Integer maxMessages) {
        if (maxMessages != null && maxMessages != 1) {
            throw new IllegalArgumentException("maxMessages must be 1 in this slice");
        }
    }

    private int leaseSeconds(Integer requested) {
        int max = maxLeaseBudgetSeconds();
        int seconds = requested == null
                ? (int) properties.getDelivery().getLease().toSeconds()
                : requested;
        if (seconds < 5 || seconds > max) {
            throw new IllegalArgumentException(
                    "leaseSeconds must be between 5 and " + max);
        }
        return seconds;
    }

    private int maxLeaseBudgetSeconds() {
        return (int) properties.getDelivery().getMaxLeaseBudget().toSeconds();
    }

    private static DeliveryOutcome parseOutcome(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("outcome must be COMPLETE, RELEASE, or FAIL");
        }
        try {
            return DeliveryOutcome.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("outcome must be COMPLETE, RELEASE, or FAIL");
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
