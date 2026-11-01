package com.acme.exchange.api.service;

import com.acme.exchange.api.config.ExchangeProperties;
import com.acme.exchange.api.web.dto.LeaseRequest;
import com.acme.exchange.api.web.dto.RenewRequest;
import com.acme.exchange.api.web.dto.SettleRequest;
import com.acme.exchange.domain.LeaseLostException;
import com.acme.exchange.persistence.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    private final ExchangeProperties properties = new ExchangeProperties();
    private DeliveryService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryService(deliveryRepository, properties);
    }

    @Test
    void rejectsABatchLeaseInThisSlice() {
        assertThatThrownBy(() -> service.lease(
                "tenant-dev", "analytics", new LeaseRequest(2, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessages");
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void rejectsALeaseOutsideTheConfiguredBudget() {
        assertThatThrownBy(() -> service.lease(
                "tenant-dev", "analytics", new LeaseRequest(1, 901)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseSeconds");
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void rejectsAnUnknownSettlementOutcome() {
        UUID deliveryId = UUID.randomUUID();
        assertThatThrownBy(() -> service.settle(
                "tenant-dev", "analytics", deliveryId, "token",
                new SettleRequest("NOPE", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void rejectsARenewalWithoutALeaseToken() {
        UUID deliveryId = UUID.randomUUID();
        assertThatThrownBy(() -> service.renew(
                "tenant-dev", "analytics", deliveryId, null, new RenewRequest(60)))
                .isInstanceOf(LeaseLostException.class);
        verifyNoInteractions(deliveryRepository);
    }
}
