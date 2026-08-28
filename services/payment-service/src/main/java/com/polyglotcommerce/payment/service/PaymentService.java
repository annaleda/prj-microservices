package com.polyglotcommerce.payment.service;

import com.polyglotcommerce.payment.dto.PaymentRequest;
import com.polyglotcommerce.payment.dto.PaymentResponse;
import com.polyglotcommerce.payment.exception.ResourceNotFoundException;
import com.polyglotcommerce.payment.model.Payment;
import com.polyglotcommerce.payment.model.PaymentStatus;
import com.polyglotcommerce.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// Nota: il documento di design prevede che questo servizio addebiti
// l'importo verso un vero provider di pagamento esterno, pubblicando poi
// payment.completed o payment.failed su Kafka. Ne' un provider reale ne'
// Kafka esistono ancora nel progetto (Phase 3 della roadmap): l'esito e'
// quindi deciso da una regola simulata, puramente dimostrativa (vedi
// isDeclinedBySimulatedGateway), cosi' che entrambi gli stati COMPLETED e
// FAILED restino raggiungibili e testabili senza un gateway reale.
@Service
public class PaymentService {

    private static final BigDecimal SIMULATED_DECLINE_THRESHOLD = new BigDecimal("10000");

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentResponse create(PaymentRequest request) {
        PaymentStatus status = isDeclinedBySimulatedGateway(request.getAmount())
                ? PaymentStatus.FAILED
                : PaymentStatus.COMPLETED;

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .method(request.getMethod())
                .status(status)
                .build();

        return PaymentResponse.fromEntity(paymentRepository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        return PaymentResponse.fromEntity(payment);
    }

    private boolean isDeclinedBySimulatedGateway(BigDecimal amount) {
        return amount.compareTo(SIMULATED_DECLINE_THRESHOLD) >= 0;
    }
}
