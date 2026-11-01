package com.acme.exchange.persistence;

import com.acme.exchange.domain.AcceptedMessage;
import com.acme.exchange.domain.DeliveryClaim;
import com.acme.exchange.domain.DeliveryMode;
import com.acme.exchange.domain.DeliveryOutcome;
import com.acme.exchange.domain.DeliveryRenewal;
import com.acme.exchange.domain.DeliverySettlement;
import com.acme.exchange.domain.LeaseLostException;
import com.acme.exchange.domain.SubscriberBinding;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeliveryRepository {

    private static final int MAX_BACKOFF_SECONDS = 300;

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public DeliveryRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    /**
     * Stages polling work items in the same transaction as acceptance. Webhook
     * rows are staged by the bridge worker in a later slice.
     */
    public void stagePullDeliveries(AcceptedMessage message, List<SubscriberBinding> bindings) {
        for (SubscriberBinding binding : bindings) {
            if (binding.deliveryMode() != DeliveryMode.PULL) {
                continue;
            }
            dsl.execute("""
                            insert into delivery
                                (id, tenant_id, flow_id, message_id, subscriber_id, generation,
                                 mode, state, attempt, next_attempt_at)
                            values (?, ?, ?, ?, ?, 0, 'PULL', 'STAGED', 0, now())
                            on conflict (tenant_id, flow_id, message_id, subscriber_id, generation)
                            do nothing
                            """,
                    UUID.randomUUID(),
                    message.tenantId(),
                    message.flowId(),
                    message.messageId(),
                    binding.subscriberId());

            dsl.execute("""
                            update message_subscriber
                            set dispatch_state = 'STAGED', updated_at = now()
                            where tenant_id = ? and flow_id = ? and message_id = ?
                              and subscriber_id = ? and generation = 0
                            """,
                    message.tenantId(),
                    message.flowId(),
                    message.messageId(),
                    binding.subscriberId());
        }
    }

    public Optional<DeliveryClaim> claimPull(
            String tenantId,
            String subscriberId,
            String leaseOwner,
            int leaseSeconds,
            String leaseTokenHash
    ) {
        return dsl.resultQuery("""
                        with due as (
                            select id
                            from delivery
                            where tenant_id = ?
                              and subscriber_id = ?
                              and mode = 'PULL'
                              and (
                                    (state = 'STAGED' and next_attempt_at <= now())
                                 or (state = 'LEASED' and lease_expires_at <= now())
                              )
                            order by next_attempt_at
                            limit 1
                            for update skip locked
                        ),
                        claimed as (
                            update delivery d
                            set state = 'LEASED',
                                lease_owner = ?,
                                lease_started_at = now(),
                                lease_expires_at = now() + (? * interval '1 second'),
                                lease_token_hash = ?,
                                fence = d.fence + 1,
                                attempt = d.attempt + 1
                            from due
                            where d.id = due.id
                            returning d.id, d.tenant_id, d.flow_id, d.message_id,
                                      d.attempt, d.lease_expires_at
                        )
                        select c.id, c.attempt, c.lease_expires_at, m.envelope
                        from claimed c
                        join message m
                          on m.tenant_id = c.tenant_id
                         and m.flow_id = c.flow_id
                         and m.message_id = c.message_id
                        """,
                        tenantId,
                        subscriberId,
                        leaseOwner,
                        leaseSeconds,
                        leaseTokenHash)
                .fetchOptional()
                .map(this::mapClaim);
    }

    public Optional<DeliveryRenewal> renew(
            UUID deliveryId,
            String tenantId,
            String subscriberId,
            String leaseTokenHash,
            int leaseSeconds,
            int maxLeaseBudgetSeconds
    ) {
        return dsl.resultQuery("""
                        update delivery
                        set lease_expires_at = now() + (? * interval '1 second')
                        where id = ?
                          and tenant_id = ?
                          and subscriber_id = ?
                          and mode = 'PULL'
                          and state = 'LEASED'
                          and lease_token_hash = ?
                          and lease_expires_at > now()
                          and now() + (? * interval '1 second')
                              <= lease_started_at + (? * interval '1 second')
                        returning id, lease_expires_at, attempt
                        """,
                        leaseSeconds,
                        deliveryId,
                        tenantId,
                        subscriberId,
                        leaseTokenHash,
                        leaseSeconds,
                        maxLeaseBudgetSeconds)
                .fetchOptional()
                .map(record -> new DeliveryRenewal(
                        record.get("id", UUID.class),
                        toInstant(record.get("lease_expires_at", OffsetDateTime.class)),
                        record.get("attempt", Integer.class)));
    }

    public DeliverySettlement settle(
            UUID deliveryId,
            String tenantId,
            String subscriberId,
            String leaseTokenHash,
            DeliveryOutcome outcome,
            String errorCode
    ) {
        LockedDelivery locked = lock(deliveryId, tenantId, subscriberId)
                .orElseThrow(() -> new LeaseLostException(deliveryId));

        if (!"LEASED".equals(locked.state())
                || !locked.leaseValid()
                || !leaseTokenHash.equals(locked.leaseTokenHash())) {
            throw new LeaseLostException(deliveryId);
        }

        return switch (outcome) {
            case COMPLETE -> complete(locked);
            case RELEASE -> retry(locked, errorCode, false);
            case FAIL -> retry(locked, errorCode, locked.attempt() >= locked.maxAttempts());
        };
    }

    private Optional<LockedDelivery> lock(UUID deliveryId, String tenantId, String subscriberId) {
        return dsl.resultQuery("""
                        select id, tenant_id, flow_id, message_id, subscriber_id, generation,
                               state, attempt, max_attempts, lease_started_at,
                               lease_token_hash,
                               (state = 'LEASED' and lease_expires_at > now()) as lease_valid
                        from delivery
                        where id = ? and tenant_id = ? and subscriber_id = ? and mode = 'PULL'
                        for update
                        """,
                        deliveryId,
                        tenantId,
                        subscriberId)
                .fetchOptional()
                .map(this::mapLocked);
    }

    private DeliverySettlement complete(LockedDelivery locked) {
        int updated = dsl.execute("""
                        update delivery
                        set state = 'DELIVERED',
                            delivered_at = now(),
                            lease_owner = null,
                            lease_expires_at = null,
                            lease_token_hash = null,
                            last_error = null
                        where id = ? and state = 'LEASED'
                        """,
                locked.id());
        if (updated != 1) {
            throw new LeaseLostException(locked.id());
        }
        updateSubscriberState(locked, "DELIVERED");
        recordAttempt(locked, "SUCCESS", null);
        return new DeliverySettlement(locked.id(), "DELIVERED", null, locked.attempt());
    }

    private DeliverySettlement retry(LockedDelivery locked, String errorCode, boolean quarantine) {
        if (quarantine) {
            dsl.execute("""
                            update delivery
                            set state = 'QUARANTINED',
                                lease_owner = null,
                                lease_expires_at = null,
                                lease_token_hash = null,
                                last_error = ?
                            where id = ?
                            """,
                    errorCode,
                    locked.id());
            updateSubscriberState(locked, "QUARANTINED");
            recordAttempt(locked, "PERMANENT", errorCode);
            return new DeliverySettlement(locked.id(), "QUARANTINED", null, locked.attempt());
        }

        int backoffSeconds = Math.min(
                MAX_BACKOFF_SECONDS, Math.max(5, 5 * Math.max(1, locked.attempt())));
        OffsetDateTime nextAttemptAt = dsl.resultQuery("""
                        update delivery
                        set state = 'STAGED',
                            next_attempt_at = now() + (? * interval '1 second'),
                            lease_owner = null,
                            lease_expires_at = null,
                            lease_token_hash = null,
                            last_error = ?
                        where id = ?
                        returning next_attempt_at
                        """,
                        backoffSeconds,
                        errorCode,
                        locked.id())
                .fetchOptional(0, OffsetDateTime.class)
                .orElseThrow(() -> new LeaseLostException(locked.id()));

        updateSubscriberState(locked, "STAGED");
        recordAttempt(locked, "RETRYABLE", errorCode);
        return new DeliverySettlement(
                locked.id(), "STAGED", toInstant(nextAttemptAt), locked.attempt());
    }

    private void updateSubscriberState(LockedDelivery locked, String state) {
        dsl.execute("""
                        update message_subscriber
                        set dispatch_state = ?, updated_at = now()
                        where tenant_id = ? and flow_id = ? and message_id = ?
                          and subscriber_id = ? and generation = ?
                        """,
                state,
                locked.tenantId(),
                locked.flowId(),
                locked.messageId(),
                locked.subscriberId(),
                locked.generation());
    }

    private void recordAttempt(LockedDelivery locked, String outcome, String errorCode) {
        dsl.execute("""
                        insert into delivery_attempt
                            (delivery_id, attempt, started_at, finished_at, outcome, error_code)
                        values (?, ?, coalesce(?::timestamptz, now()), now(), ?, ?)
                        on conflict (delivery_id, attempt) do nothing
                        """,
                locked.id(),
                locked.attempt(),
                locked.leaseStartedAt(),
                outcome,
                errorCode);
    }

    private DeliveryClaim mapClaim(Record record) {
        return new DeliveryClaim(
                record.get("id", UUID.class),
                toInstant(record.get("lease_expires_at", OffsetDateTime.class)),
                record.get("attempt", Integer.class),
                readJson(record.get("envelope", JSONB.class)));
    }

    private LockedDelivery mapLocked(Record record) {
        return new LockedDelivery(
                record.get("id", UUID.class),
                record.get("tenant_id", String.class),
                record.get("flow_id", String.class),
                record.get("message_id", String.class),
                record.get("subscriber_id", String.class),
                record.get("generation", Long.class),
                record.get("state", String.class),
                record.get("attempt", Integer.class),
                record.get("max_attempts", Integer.class),
                record.get("lease_started_at", OffsetDateTime.class),
                record.get("lease_token_hash", String.class),
                Boolean.TRUE.equals(record.get("lease_valid", Boolean.class)));
    }

    private JsonNode readJson(JSONB jsonb) {
        try {
            return objectMapper.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored message envelope is not valid JSON", e);
        }
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value.toInstant();
    }

    private record LockedDelivery(
            UUID id,
            String tenantId,
            String flowId,
            String messageId,
            String subscriberId,
            long generation,
            String state,
            int attempt,
            int maxAttempts,
            OffsetDateTime leaseStartedAt,
            String leaseTokenHash,
            boolean leaseValid
    ) {
    }
}
