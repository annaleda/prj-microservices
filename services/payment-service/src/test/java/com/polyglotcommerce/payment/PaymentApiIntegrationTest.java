package com.polyglotcommerce.payment;

import com.polyglotcommerce.payment.dto.PaymentRequest;
import com.polyglotcommerce.payment.dto.PaymentResponse;
import com.polyglotcommerce.payment.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createAndReadSuccessfulPayment() {
        PaymentRequest request = new PaymentRequest(1L, new BigDecimal("59.80"), "CARD");

        ResponseEntity<PaymentResponse> createResponse =
                restTemplate.postForEntity("/api/payments", request, PaymentResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentResponse created = createResponse.getBody();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        ResponseEntity<PaymentResponse> getResponse =
                restTemplate.getForEntity("/api/payments/" + created.getId(), PaymentResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getOrderId()).isEqualTo(1L);
    }

    @Test
    void paymentAboveSimulatedThresholdIsDeclined() {
        PaymentRequest request = new PaymentRequest(2L, new BigDecimal("15000.00"), "CARD");

        ResponseEntity<PaymentResponse> response =
                restTemplate.postForEntity("/api/payments", request, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void getUnknownPaymentReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/payments/999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
