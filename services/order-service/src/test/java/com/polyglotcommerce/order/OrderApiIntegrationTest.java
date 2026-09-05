package com.polyglotcommerce.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.polyglotcommerce.order.dto.OrderItemRequest;
import com.polyglotcommerce.order.dto.OrderRequest;
import com.polyglotcommerce.order.dto.OrderResponse;
import com.polyglotcommerce.order.dto.OrderStatusUpdateRequest;
import com.polyglotcommerce.order.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    // Il servizio pubblica order.created e consuma gli esiti della saga:
    // serve un broker vero, non un mock, come gia' per il database.
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private OrderRequest sampleOrderRequest() {
        OrderItemRequest item = new OrderItemRequest(1L, "Wireless Mouse", 2, new BigDecimal("29.90"));
        return new OrderRequest("customer@example.com", List.of(item));
    }

    @Test
    void createAndReadOrder() {
        ResponseEntity<OrderResponse> createResponse =
                restTemplate.postForEntity("/api/orders", sampleOrderRequest(), OrderResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse created = createResponse.getBody();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.getTotalAmount()).isEqualByComparingTo("59.80");
        assertThat(created.getItems()).hasSize(1);

        ResponseEntity<OrderResponse> getResponse =
                restTemplate.getForEntity("/api/orders/" + created.getId(), OrderResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getCustomerEmail()).isEqualTo("customer@example.com");

        ResponseEntity<OrderResponse[]> listResponse =
                restTemplate.getForEntity("/api/orders", OrderResponse[].class);
        assertThat(listResponse.getBody()).extracting(OrderResponse::getId).contains(created.getId());
    }

    @Test
    void getUnknownOrderReturns404() {
        // Il body di errore (ApiError) ha un campo "status" numerico che
        // andrebbe in conflitto con l'enum OrderStatus se deserializzato
        // come OrderResponse: qui interessa solo il codice HTTP.
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/orders/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateStatusLifecycleAndRejectAfterCancellation() {
        OrderResponse created = restTemplate.postForEntity("/api/orders", sampleOrderRequest(), OrderResponse.class)
                .getBody();

        ResponseEntity<OrderResponse> confirmResponse = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(new OrderStatusUpdateRequest(OrderStatus.CONFIRMED)),
                OrderResponse.class);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody().getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        ResponseEntity<OrderResponse> cancelResponse = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(new OrderStatusUpdateRequest(OrderStatus.CANCELLED)),
                OrderResponse.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().getStatus()).isEqualTo(OrderStatus.CANCELLED);

        ResponseEntity<String> rejectedResponse = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(new OrderStatusUpdateRequest(OrderStatus.CONFIRMED)),
                String.class);
        assertThat(rejectedResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void creatingAnOrderPublishesOrderCreated() {
        OrderResponse created = restTemplate.postForEntity("/api/orders", sampleOrderRequest(), OrderResponse.class)
                .getBody();

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "order.created", created.getId(), Duration.ofSeconds(20));

        assertThat(envelope.path("eventType").asText()).isEqualTo("ORDER_CREATED");
        assertThat(envelope.path("source").asText()).isEqualTo("order-service");
        assertThat(envelope.path("correlationId").asText()).isNotEmpty();

        JsonNode data = envelope.path("data");
        assertThat(data.path("customerEmail").asText()).isEqualTo("customer@example.com");
        assertThat(new BigDecimal(data.path("totalAmount").asText())).isEqualByComparingTo("59.80");
        assertThat(data.path("items")).hasSize(1);
        assertThat(data.path("items").get(0).path("productId").asLong()).isEqualTo(1L);
        assertThat(data.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void sagaOutcomeConfirmsTheOrder() {
        OrderResponse created = restTemplate.postForEntity("/api/orders", sampleOrderRequest(), OrderResponse.class)
                .getBody();

        // L'esito della saga arriva dall'Integration Service: qui lo si
        // simula pubblicando direttamente l'evento sul topic.
        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.updated", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_UPDATED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"status\":\"CONFIRMED\"}"));

        await(() -> restTemplate.getForObject("/api/orders/" + created.getId(), OrderResponse.class)
                .getStatus() == OrderStatus.CONFIRMED);
    }

    @Test
    void sagaOutcomeIsIdempotentOnACancelledOrder() {
        OrderResponse created = restTemplate.postForEntity("/api/orders", sampleOrderRequest(), OrderResponse.class)
                .getBody();

        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"reason\":\"Payment failed\"}"));

        await(() -> restTemplate.getForObject("/api/orders/" + created.getId(), OrderResponse.class)
                .getStatus() == OrderStatus.CANCELLED);

        // Ri-consegna dello stesso evento: nessuna eccezione, nessun cambio
        // di stato (un ordine annullato resta annullato).
        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"reason\":\"Payment failed\"}"));

        assertThat(restTemplate.getForObject("/api/orders/" + created.getId(), OrderResponse.class).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    /** Attesa attiva su una condizione che dipende da un consumer asincrono. */
    private void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met within 20s");
    }
}
