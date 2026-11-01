package com.acme.exchange.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class ExchangeApiIntegrationTest {

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

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void acceptsDurablyAndTreatsRetriesIdempotently() throws Exception {
        String body = """
                {
                  "specversion": "1.0",
                  "id": "it-1",
                  "source": "test",
                  "type": "order.created",
                  "data": {"orderId": "123", "amount": 42.5}
                }
                """;

        HttpResponse<String> first = post("/v1/flows/orders/messages", body);
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.headers().firstValue("Location"))
                .hasValueSatisfying(location ->
                        assertThat(location).endsWith("/v1/flows/orders/publications/it-1"));
        assertThat(first.headers().firstValue("X-Acceptance-Time")).isPresent();

        assertThat(count("select count(*) from message where message_id = 'it-1'")).isEqualTo(1);
        assertThat(count("select count(*) from publish_outbox where message_id = 'it-1'")).isEqualTo(1);
        assertThat(count("select count(*) from message_subscriber where message_id = 'it-1'")).isEqualTo(2);

        HttpResponse<String> retry = post("/v1/flows/orders/messages", body);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(retry.body()).contains("\"replayed\":true");
        assertThat(count("select count(*) from message where message_id = 'it-1'")).isEqualTo(1);
        assertThat(count("select count(*) from publish_outbox where message_id = 'it-1'")).isEqualTo(1);

        HttpResponse<String> conflict = post(
                "/v1/flows/orders/messages",
                body.replace("42.5", "99.9"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("MESSAGE_ID_CONFLICT");

        HttpResponse<String> status = get("/v1/flows/orders/publications/it-1");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"state\":\"PUBLISHING\"");
        assertThat(status.body()).contains("\"expectedSubscribers\":2");

        assertThat(count("select count(*) from delivery where message_id = 'it-1' and mode = 'PULL'"))
                .isEqualTo(1);
        assertThat(dispatchState("it-1", "analytics")).isEqualTo("STAGED");
        assertThat(dispatchState("it-1", "billing")).isEqualTo("EXPECTED");

        HttpResponse<String> leaseResponse = post(
                "/v1/deliveries/lease", "{\"maxMessages\":1,\"leaseSeconds\":60}");
        assertThat(leaseResponse.statusCode()).isEqualTo(200);
        JsonNode lease = mapper.readTree(leaseResponse.body());
        assertThat(lease.path("attempt").asInt()).isEqualTo(1);
        assertThat(lease.path("envelope").path("id").asText()).isEqualTo("it-1");
        assertThat(lease.path("deliveryId").asText()).isNotBlank();
        assertThat(lease.path("leaseToken").asText()).isNotBlank();

        assertThat(post("/v1/deliveries/lease", "{}").statusCode()).isEqualTo(204);

        String deliveryId = lease.path("deliveryId").asText();
        String leaseToken = lease.path("leaseToken").asText();

        HttpResponse<String> renew = postWithToken(
                "/v1/deliveries/" + deliveryId + "/renew",
                "{\"leaseSeconds\":120}",
                leaseToken);
        assertThat(renew.statusCode()).isEqualTo(200);
        assertThat(renew.body()).contains("leaseExpiresAt");

        HttpResponse<String> settle = postWithToken(
                "/v1/deliveries/" + deliveryId + "/settle",
                "{\"outcome\":\"COMPLETE\"}",
                leaseToken);
        assertThat(settle.statusCode()).isEqualTo(200);
        assertThat(settle.body()).contains("\"state\":\"DELIVERED\"");
        assertThat(jdbc.queryForObject(
                "select count(*) from delivery where id = ? and state = 'DELIVERED'",
                Integer.class,
                UUID.fromString(deliveryId))).isEqualTo(1);
        assertThat(dispatchState("it-1", "analytics")).isEqualTo("DELIVERED");

        assertThat(post("/v1/deliveries/lease", "{}").statusCode()).isEqualTo(204);
    }

    @Test
    void rejectsAStaleLeaseTokenAfterTheDeliveryIsReclaimed() throws Exception {
        String body = """
                {
                  "specversion": "1.0",
                  "id": "it-lease-2",
                  "source": "test",
                  "type": "order.created",
                  "data": {"orderId": "456"}
                }
                """;
        assertThat(post("/v1/flows/orders/messages", body).statusCode()).isEqualTo(202);

        HttpResponse<String> firstLease = post("/v1/deliveries/lease", "{}");
        assertThat(firstLease.statusCode()).isEqualTo(200);
        JsonNode first = mapper.readTree(firstLease.body());
        String deliveryId = first.path("deliveryId").asText();
        String firstToken = first.path("leaseToken").asText();

        jdbc.update(
                "update delivery set lease_expires_at = now() - interval '1 second' where id = ?",
                UUID.fromString(deliveryId));

        HttpResponse<String> secondLease = post("/v1/deliveries/lease", "{}");
        assertThat(secondLease.statusCode()).isEqualTo(200);
        JsonNode second = mapper.readTree(secondLease.body());
        assertThat(second.path("deliveryId").asText()).isEqualTo(deliveryId);
        assertThat(second.path("attempt").asInt()).isEqualTo(2);
        String secondToken = second.path("leaseToken").asText();

        HttpResponse<String> staleSettle = postWithToken(
                "/v1/deliveries/" + deliveryId + "/settle",
                "{\"outcome\":\"COMPLETE\"}",
                firstToken);
        assertThat(staleSettle.statusCode()).isEqualTo(409);
        assertThat(staleSettle.body()).contains("LEASE_LOST");

        HttpResponse<String> settle = postWithToken(
                "/v1/deliveries/" + deliveryId + "/settle",
                "{\"outcome\":\"COMPLETE\"}",
                secondToken);
        assertThat(settle.statusCode()).isEqualTo(200);
        assertThat(settle.body()).contains("\"state\":\"DELIVERED\"");
    }

    @Test
    void rejectsAnEnvelopeThatDoesNotMatchTheActiveSchema() throws Exception {
        String body = """
                {
                  "specversion": "1.0",
                  "id": "it-schema-invalid",
                  "source": "test",
                  "type": "order.created",
                  "data": {"amount": 42.5}
                }
                """;

        HttpResponse<String> response = post("/v1/flows/orders/messages", body);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("SCHEMA_VALIDATION_FAILED");
        assertThat(count("select count(*) from message where message_id = 'it-schema-invalid'"))
                .isZero();
        assertThat(count("select count(*) from publish_outbox where message_id = 'it-schema-invalid'"))
                .isZero();
    }

    @Test
    void quarantinesADeliveryAfterTheFinalFailedAttempt() throws Exception {
        String body = """
                {
                  "specversion": "1.0",
                  "id": "it-lease-3",
                  "source": "test",
                  "type": "order.created",
                  "data": {"orderId": "789"}
                }
                """;
        assertThat(post("/v1/flows/orders/messages", body).statusCode()).isEqualTo(202);
        jdbc.update("update delivery set max_attempts = 1 where message_id = 'it-lease-3'");

        HttpResponse<String> leaseResponse = post("/v1/deliveries/lease", "{}");
        assertThat(leaseResponse.statusCode()).isEqualTo(200);
        JsonNode lease = mapper.readTree(leaseResponse.body());
        String deliveryId = lease.path("deliveryId").asText();
        String leaseToken = lease.path("leaseToken").asText();

        HttpResponse<String> settle = postWithToken(
                "/v1/deliveries/" + deliveryId + "/settle",
                "{\"outcome\":\"FAIL\",\"errorCode\":\"consumer_error\"}",
                leaseToken);

        assertThat(settle.statusCode()).isEqualTo(200);
        assertThat(settle.body()).contains("\"state\":\"QUARANTINED\"");
        assertThat(jdbc.queryForObject(
                "select state from delivery where id = ?",
                String.class,
                UUID.fromString(deliveryId))).isEqualTo("QUARANTINED");
        assertThat(dispatchState("it-lease-3", "analytics")).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject(
                "select outcome from delivery_attempt where delivery_id = ? and attempt = 1",
                String.class,
                UUID.fromString(deliveryId))).isEqualTo("PERMANENT");
    }

    @Test
    void releasesADeliveryForRetryAndIncrementsTheAttempt() throws Exception {
        String body = """
                {
                  "specversion": "1.0",
                  "id": "it-lease-4",
                  "source": "test",
                  "type": "order.created",
                  "data": {"orderId": "101"}
                }
                """;
        assertThat(post("/v1/flows/orders/messages", body).statusCode()).isEqualTo(202);

        HttpResponse<String> firstLease = post("/v1/deliveries/lease", "{}");
        assertThat(firstLease.statusCode()).isEqualTo(200);
        JsonNode first = mapper.readTree(firstLease.body());
        String deliveryId = first.path("deliveryId").asText();
        String firstToken = first.path("leaseToken").asText();

        HttpResponse<String> release = postWithToken(
                "/v1/deliveries/" + deliveryId + "/settle",
                "{\"outcome\":\"RELEASE\"}",
                firstToken);
        assertThat(release.statusCode()).isEqualTo(200);
        assertThat(release.body()).contains("\"state\":\"STAGED\"");
        assertThat(release.body()).contains("nextAttemptAt");

        jdbc.update(
                "update delivery set next_attempt_at = now() where id = ?",
                UUID.fromString(deliveryId));

        HttpResponse<String> secondLease = post("/v1/deliveries/lease", "{}");
        assertThat(secondLease.statusCode()).isEqualTo(200);
        JsonNode second = mapper.readTree(secondLease.body());
        assertThat(second.path("deliveryId").asText()).isEqualTo(deliveryId);
        assertThat(second.path("attempt").asInt()).isEqualTo(2);
        String secondToken = second.path("leaseToken").asText();

        HttpResponse<String> settle = postWithToken(
                "/v1/deliveries/" + deliveryId + "/settle",
                "{\"outcome\":\"COMPLETE\"}",
                secondToken);
        assertThat(settle.statusCode()).isEqualTo(200);
        assertThat(dispatchState("it-lease-4", "analytics")).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForList(
                "select outcome from delivery_attempt where delivery_id = ? order by attempt",
                String.class,
                UUID.fromString(deliveryId))).containsExactly("RETRYABLE", "SUCCESS");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "tenant-dev")
                .header("X-Subscriber-Id", "analytics")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithToken(String path, String body, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "tenant-dev")
                .header("X-Subscriber-Id", "analytics")
                .header("X-Lease-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("X-Tenant-Id", "tenant-dev")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String dispatchState(String messageId, String subscriberId) {
        return jdbc.queryForObject("""
                        select dispatch_state
                        from message_subscriber
                        where message_id = ? and subscriber_id = ?
                        """,
                String.class,
                messageId,
                subscriberId);
    }
}
