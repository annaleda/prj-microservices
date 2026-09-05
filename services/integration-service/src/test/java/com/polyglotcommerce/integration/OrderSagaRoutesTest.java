package com.polyglotcommerce.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.polyglotcommerce.integration.saga.SagaStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica l'orchestrazione contro un broker Kafka reale (Testcontainers):
 * si pubblicano gli eventi che gli altri servizi produrrebbero e si
 * controlla quale passo la saga decide di conseguenza.
 *
 * Gli altri servizi non servono: l'Integration Service parla solo di
 * eventi.
 */
@Testcontainers
@SpringBootTest
class OrderSagaRoutesTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("camel.component.kafka.brokers", kafka::getBootstrapServers);
    }

    @Autowired
    private SagaStateStore stateStore;

    @Test
    void reservedStockLeadsToAPaymentRequestWithTheOrderAmount() {
        long orderId = 200L;
        publishOrderCreated(orderId, "59.80");

        publish("inventory.reserved", orderId, "INVENTORY_RESERVED",
                "{\"orderId\":" + orderId + ",\"reservations\":[{\"reservationId\":1,\"productId\":1,\"quantity\":2}]}");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "payment.requested", orderId, Duration.ofSeconds(30));

        assertThat(envelope.path("eventType").asText()).isEqualTo("PAYMENT_REQUESTED");
        assertThat(envelope.path("source").asText()).isEqualTo("integration-service");
        // Il correlationId dell'ordine viene propagato al passo successivo.
        assertThat(envelope.path("correlationId").asText()).isEqualTo("correlation-" + orderId);
        assertThat(new BigDecimal(envelope.path("data").path("amount").asText()))
                .isEqualByComparingTo("59.80");
    }

    @Test
    void rejectedStockCancelsTheOrder() {
        long orderId = 201L;
        publishOrderCreated(orderId, "10.00");

        publish("inventory.rejected", orderId, "INVENTORY_REJECTED",
                "{\"orderId\":" + orderId + ",\"reason\":\"Insufficient stock for product 1\"}");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "order.cancelled", orderId, Duration.ofSeconds(30));

        assertThat(envelope.path("eventType").asText()).isEqualTo("ORDER_CANCELLED");
        assertThat(envelope.path("data").path("reason").asText()).contains("Insufficient stock");
    }

    @Test
    void completedPaymentConfirmsTheOrder() {
        long orderId = 202L;
        publishOrderCreated(orderId, "20.00");

        publish("payment.completed", orderId, "PAYMENT_COMPLETED",
                "{\"orderId\":" + orderId + ",\"paymentId\":7,\"amount\":20.00,\"status\":\"COMPLETED\"}");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "order.updated", orderId, Duration.ofSeconds(30));

        assertThat(envelope.path("data").path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void failedPaymentCancelsTheOrderSoThatStockIsReleased() {
        long orderId = 203L;
        publishOrderCreated(orderId, "15000.00");

        publish("payment.failed", orderId, "PAYMENT_FAILED",
                "{\"orderId\":" + orderId + ",\"paymentId\":8,\"status\":\"FAILED\",\"reason\":\"Declined by payment gateway\"}");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "order.cancelled", orderId, Duration.ofSeconds(30));

        assertThat(envelope.path("data").path("reason").asText()).contains("Declined by payment gateway");
    }

    @Test
    void reservedStockWithoutSagaStateCancelsTheOrder() {
        // Nessun order.created: e' la situazione in cui l'orchestratore e'
        // ripartito mentre la saga era aperta e ha perso lo stato in memoria.
        long orderId = 204L;

        publish("inventory.reserved", orderId, "INVENTORY_RESERVED",
                "{\"orderId\":" + orderId + ",\"reservations\":[]}");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "order.cancelled", orderId, Duration.ofSeconds(30));

        assertThat(envelope.path("data").path("reason").asText()).contains("Saga state lost");
    }

    /**
     * Apre la saga e attende che l'orchestratore abbia registrato lo stato:
     * senza questa attesa il passo successivo, che viaggia su un altro
     * topic e quindi su un'altra rotta, potrebbe arrivare per primo.
     */
    private void publishOrderCreated(long orderId, String totalAmount) {
        publish("order.created", orderId, "ORDER_CREATED",
                "{\"orderId\":" + orderId + ",\"customerEmail\":\"customer@example.com\","
                        + "\"totalAmount\":" + totalAmount + ","
                        + "\"items\":[{\"productId\":1,\"quantity\":2,\"unitPrice\":29.90}]}");

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (stateStore.find(orderId).isPresent()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Saga for order " + orderId + " was not started within 30s");
    }

    private void publish(String topic, long orderId, String eventType, String dataJson) {
        KafkaTestSupport.send(kafka.getBootstrapServers(), topic, String.valueOf(orderId),
                KafkaTestSupport.envelope(eventType, "correlation-" + orderId, dataJson));
    }
}
