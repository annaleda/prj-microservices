package com.polyglotcommerce.payment.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.polyglotcommerce.payment.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Corpo ({@code data}) degli eventi {@code payment.completed} e {@code payment.failed}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentOutcomePayload {

    private Long paymentId;
    private Long orderId;
    private BigDecimal amount;
    private PaymentStatus status;
    /** Valorizzato solo sui pagamenti rifiutati. */
    private String reason;
}
