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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "tenant-dev")
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
}
