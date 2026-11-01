package com.acme.exchange.worker.outbox;

import com.acme.exchange.domain.OutboxEntry;
import com.acme.exchange.persistence.OutboxRepository;
import com.acme.exchange.worker.broker.MessageBrokerPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "exchange.worker.outbox.poll-interval-ms=3600000",
        "exchange.broker.adapter=test"
})
@Import(OutboxRepositoryIntegrationTest.TestBrokerConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private MessageBrokerPort broker;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dispatchesThroughTheBrokerPortAndMarksPublished() {
        insertMessageAndOutbox("worker-it-3");

        dispatcher.dispatch();

        assertThat(state("worker-it-3")).isEqualTo("PUBLISHED");
        assertThat(transportId("worker-it-3")).isEqualTo("test-transport");
        assertThat(((CapturingBroker) broker).lastEntry()).isNotNull();
        assertThat(((CapturingBroker) broker).lastEntry().messageId()).isEqualTo("worker-it-3");
        assertThat(((CapturingBroker) broker).lastEntry().envelope().path("data").path("amount").asDouble())
                .isEqualTo(42.5);
    }

    @Test
    void claimsAPendingRowAndMarksItPublishedWithTheFence() {
        insertMessageAndOutbox("worker-it-1");

        List<OutboxEntry> claimed = outboxRepository.claimBatch(
                "test-owner", 10, Duration.ofSeconds(30));

        assertThat(claimed).hasSize(1);
        OutboxEntry entry = claimed.get(0);
        assertThat(entry.messageId()).isEqualTo("worker-it-1");
        assertThat(entry.attempt()).isEqualTo(1);
        assertThat(entry.fence()).isEqualTo(1L);
        assertThat(entry.envelope().path("data").path("amount").asDouble()).isEqualTo(42.5);

        assertThat(outboxRepository.markPublished(entry.id(), entry.fence(), "transport-1")).isTrue();
        assertThat(state("worker-it-1")).isEqualTo("PUBLISHED");
        assertThat(transportId("worker-it-1")).isEqualTo("transport-1");
    }

    @Test
    void reclaimsAClaimedRowWhoseLeaseExpired() {
        insertMessageAndOutbox("worker-it-2");

        List<OutboxEntry> firstClaim = outboxRepository.claimBatch(
                "owner-1", 10, Duration.ofSeconds(30));
        assertThat(firstClaim).hasSize(1);

        jdbc.update("""
                update publish_outbox
                set lease_expires_at = now() - interval '1 second'
                where message_id = 'worker-it-2'
                """);

        List<OutboxEntry> secondClaim = outboxRepository.claimBatch(
                "owner-2", 10, Duration.ofSeconds(30));

        assertThat(secondClaim).hasSize(1);
        assertThat(secondClaim.get(0).attempt()).isEqualTo(2);
        assertThat(secondClaim.get(0).fence()).isEqualTo(2L);
    }

    private void insertMessageAndOutbox(String messageId) {
        jdbc.update("""
                        insert into message
                            (tenant_id, flow_id, message_id, revision, content_hash, envelope,
                             headers, route_revision, accepted_at, expires_at)
                        values
                            (?, ?, ?, ?, ?, ?::jsonb, '{}'::jsonb, ?, now(), now() + interval '30 days')
                        """,
                "tenant-dev",
                "orders",
                messageId,
                1L,
                "hash-" + messageId,
                """
                        {"specversion":"1.0","id":"%s","source":"test","type":"order.created","data":{"amount":42.5}}
                        """.formatted(messageId),
                1L);

        jdbc.update("""
                        insert into publish_outbox
                            (tenant_id, flow_id, message_id, state, next_attempt_at)
                        values (?, ?, ?, 'PENDING', now())
                        """,
                "tenant-dev",
                "orders",
                messageId);
    }

    private String state(String messageId) {
        return jdbc.queryForObject(
                "select state from publish_outbox where message_id = ?",
                String.class,
                messageId);
    }

    private String transportId(String messageId) {
        return jdbc.queryForObject(
                "select transport_id from publish_outbox where message_id = ?",
                String.class,
                messageId);
    }

    @TestConfiguration
    static class TestBrokerConfig {

        @Bean
        MessageBrokerPort testBroker() {
            return new CapturingBroker();
        }
    }

    static final class CapturingBroker implements MessageBrokerPort {

        private final AtomicReference<OutboxEntry> last = new AtomicReference<>();

        @Override
        public String send(OutboxEntry entry) {
            last.set(entry);
            return "test-transport";
        }

        OutboxEntry lastEntry() {
            return last.get();
        }
    }
}
