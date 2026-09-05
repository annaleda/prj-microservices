package com.polyglotcommerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.polyglotcommerce.payment.dto.PaymentRequest;
import com.polyglotcommerce.payment.dto.PaymentResponse;
import com.polyglotcommerce.payment.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
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

import static com.polyglotcommerce.payment.TestJwtSupport.ADMIN;
import static com.polyglotcommerce.payment.TestJwtSupport.ANONYMOUS;
import static com.polyglotcommerce.payment.TestJwtSupport.CUSTOMER;
import static com.polyglotcommerce.payment.TestJwtSupport.SUPPORT;
import static com.polyglotcommerce.payment.TestJwtSupport.as;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
class PaymentApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    // Il servizio consuma payment.requested e pubblica l'esito: serve un
    // broker vero, non un mock, come gia' per il database.
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

    @Test
    void createAndReadSuccessfulPayment() {
        PaymentRequest request = new PaymentRequest(1L, new BigDecimal("59.80"), "CARD");

        ResponseEntity<PaymentResponse> createResponse = restTemplate.exchange(
                "/api/payments", HttpMethod.POST, as(ADMIN, request), PaymentResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentResponse created = createResponse.getBody();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        ResponseEntity<PaymentResponse> getResponse = restTemplate.exchange(
                "/api/payments/" + created.getId(), HttpMethod.GET, as(ADMIN), PaymentResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getOrderId()).isEqualTo(1L);
    }

    @Test
    void paymentAboveSimulatedThresholdIsDeclined() {
        PaymentRequest request = new PaymentRequest(2L, new BigDecimal("15000.00"), "CARD");

        ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                "/api/payments", HttpMethod.POST, as(ADMIN, request), PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void getUnknownPaymentReturns404() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/payments/999999", HttpMethod.GET, as(ADMIN), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void paymentRequestedEventProducesPaymentCompleted() {
        long orderId = 100L;
        requestPayment(orderId, "59.80");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "payment.completed", orderId, Duration.ofSeconds(20));

        assertThat(envelope.path("eventType").asText()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(envelope.path("source").asText()).isEqualTo("payment-service");
        assertThat(envelope.path("correlationId").asText()).isEqualTo("test-correlation");

        JsonNode data = envelope.path("data");
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(new BigDecimal(data.path("amount").asText())).isEqualByComparingTo("59.80");
        assertThat(data.path("paymentId").asLong()).isPositive();
        assertThat(data.has("reason")).isFalse();
    }

    @Test
    void declinedPaymentProducesPaymentFailedWithReason() {
        long orderId = 101L;
        requestPayment(orderId, "15000.00");

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "payment.failed", orderId, Duration.ofSeconds(20));

        assertThat(envelope.path("eventType").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(envelope.path("data").path("status").asText()).isEqualTo("FAILED");
        assertThat(envelope.path("data").path("reason").asText()).isNotEmpty();
    }

    @Test
    void redeliveredPaymentRequestIsNotChargedTwice() {
        long orderId = 102L;
        requestPayment(orderId, "42.00");
        KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "payment.completed", orderId, Duration.ofSeconds(20));

        // Stesso evento consegnato una seconda volta (at-least-once): non
        // deve nascere un secondo pagamento, ma l'esito gia' registrato va
        // ripubblicato — l'orchestratore potrebbe non aver visto il primo.
        requestPayment(orderId, "42.00");

        List<JsonNode> outcomes = KafkaTestSupport.awaitEvents(
                kafka.getBootstrapServers(), "payment.completed", orderId, 2, Duration.ofSeconds(20));

        assertThat(outcomes.get(1).path("data").path("paymentId").asLong())
                .isEqualTo(outcomes.get(0).path("data").path("paymentId").asLong());
    }

    private void requestPayment(long orderId, String amount) {
        KafkaTestSupport.send(kafka.getBootstrapServers(), "payment.requested", String.valueOf(orderId),
                KafkaTestSupport.envelope("PAYMENT_REQUESTED", "test-correlation",
                        "{\"orderId\":" + orderId + ",\"amount\":" + amount + ",\"method\":\"CARD\"}"));
    }

    @Test
    void paymentsApiIsNotPublic() {
        PaymentRequest request = new PaymentRequest(500L, new BigDecimal("10.00"), "CARD");

        assertThat(restTemplate.exchange("/api/payments", HttpMethod.POST, as(ANONYMOUS, request), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/api/payments/1", HttpMethod.GET, as(ANONYMOUS), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customersCannotUseThePaymentsApi() {
        PaymentRequest request = new PaymentRequest(501L, new BigDecimal("10.00"), "CARD");

        assertThat(restTemplate.exchange("/api/payments", HttpMethod.POST, as(CUSTOMER, request), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supportCanReadButNotCreatePayments() {
        PaymentResponse created = restTemplate.exchange("/api/payments", HttpMethod.POST,
                as(ADMIN, new PaymentRequest(502L, new BigDecimal("12.00"), "CARD")),
                PaymentResponse.class).getBody();

        assertThat(restTemplate.exchange("/api/payments/" + created.getId(), HttpMethod.GET,
                as(SUPPORT), PaymentResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(restTemplate.exchange("/api/payments", HttpMethod.POST,
                as(SUPPORT, new PaymentRequest(503L, new BigDecimal("12.00"), "CARD")), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
