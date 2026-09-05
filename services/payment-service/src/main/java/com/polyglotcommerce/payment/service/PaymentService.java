package com.polyglotcommerce.payment.service;

import com.polyglotcommerce.payment.dto.PaymentRequest;
import com.polyglotcommerce.payment.dto.PaymentResponse;
import com.polyglotcommerce.payment.event.EventEnvelope;
import com.polyglotcommerce.payment.event.EventTopics;
import com.polyglotcommerce.payment.event.OutboundEvent;
import com.polyglotcommerce.payment.event.payload.PaymentOutcomePayload;
import com.polyglotcommerce.payment.exception.ResourceNotFoundException;
import com.polyglotcommerce.payment.model.Payment;
import com.polyglotcommerce.payment.model.PaymentStatus;
import com.polyglotcommerce.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

// Nota: il documento di design prevede che questo servizio addebiti
// l'importo verso un vero provider di pagamento esterno. Un provider reale
// non esiste in questo progetto: l'esito e' quindi deciso da una regola
// simulata, puramente dimostrativa (vedi isDeclinedBySimulatedGateway),
// cosi' che entrambi gli stati COMPLETED e FAILED restino raggiungibili e
// testabili — anche il ramo di fallimento della saga.
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final BigDecimal SIMULATED_DECLINE_THRESHOLD = new BigDecimal("10000");

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository, ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creazione diretta via API REST. Non fa parte della saga e non pubblica
     * eventi: resta come punto di ingresso manuale (prove, back office). Il
     * percorso normale e' {@link #processPaymentRequest}, innescato
     * dall'evento {@code payment.requested}.
     */
    @Transactional
    public PaymentResponse create(PaymentRequest request) {
        Payment payment = charge(request.getOrderId(), request.getAmount(), request.getMethod());
        return PaymentResponse.fromEntity(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        return PaymentResponse.fromEntity(payment);
    }

    /**
     * Passo della saga: addebita e comunica l'esito all'orchestratore.
     *
     * E' idempotente sull'ordine — se un pagamento per quell'ordine esiste
     * gia' (ri-consegna dello stesso evento da parte di Kafka) non viene
     * addebitato una seconda volta, ma l'esito gia' registrato viene
     * ripubblicato: l'orchestratore potrebbe non aver mai ricevuto il primo.
     */
    @Transactional
    public void processPaymentRequest(Long orderId, BigDecimal amount, String method, String correlationId) {
        Optional<Payment> existing = paymentRepository.findFirstByOrderIdOrderByIdAsc(orderId);
        if (existing.isPresent()) {
            log.info("Payment for order {} already processed ({}): re-publishing outcome",
                    orderId, existing.get().getStatus());
            publishOutcome(existing.get(), correlationId);
            return;
        }

        Payment payment = charge(orderId, amount, method);
        publishOutcome(payment, correlationId);
    }

    private Payment charge(Long orderId, BigDecimal amount, String method) {
        PaymentStatus status = isDeclinedBySimulatedGateway(amount)
                ? PaymentStatus.FAILED
                : PaymentStatus.COMPLETED;

        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(amount)
                .method(method)
                .status(status)
                .build();

        return paymentRepository.save(payment);
    }

    private void publishOutcome(Payment payment, String correlationId) {
        boolean completed = payment.getStatus() == PaymentStatus.COMPLETED;

        PaymentOutcomePayload payload = PaymentOutcomePayload.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .reason(completed ? null : "Declined by payment gateway")
                .build();

        EventEnvelope<PaymentOutcomePayload> envelope = EventEnvelope.of(
                completed ? "PAYMENT_COMPLETED" : "PAYMENT_FAILED", correlationId, payload);

        eventPublisher.publishEvent(new OutboundEvent(
                completed ? EventTopics.PAYMENT_COMPLETED : EventTopics.PAYMENT_FAILED,
                String.valueOf(payment.getOrderId()),
                envelope));
    }

    private boolean isDeclinedBySimulatedGateway(BigDecimal amount) {
        return amount.compareTo(SIMULATED_DECLINE_THRESHOLD) >= 0;
    }
}
