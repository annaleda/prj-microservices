package com.polyglotcommerce.payment.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyglotcommerce.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Consuma {@code payment.requested}, emesso dall'Integration Service dopo
 * che le scorte sono state riservate, e pubblica l'esito
 * ({@code payment.completed} / {@code payment.failed}).
 */
@Component
public class PaymentRequestListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestListener.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentRequestListener(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = EventTopics.PAYMENT_REQUESTED, groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentRequested(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        JsonNode data = envelope.path("data");

        Long orderId = data.path("orderId").asLong();
        // asText + new BigDecimal, non decimalValue(): Jackson mappa i
        // numeri decimali su double, che per un importo non va bene.
        BigDecimal amount = new BigDecimal(data.path("amount").asText());
        String method = data.path("method").asText("CARD");
        String correlationId = envelope.path("correlationId").asText(null);

        log.info("Payment requested for order {} (amount={}, correlationId={})", orderId, amount, correlationId);
        paymentService.processPaymentRequest(orderId, amount, method, correlationId);
    }
}
