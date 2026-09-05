package com.polyglotcommerce.payment.event;

/** Topic dell'event catalog usati da questo servizio (documento di design, sezione 8). */
public final class EventTopics {

    public static final String PAYMENT_REQUESTED = "payment.requested";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String SAGA_DLQ = "saga.dlq";

    private EventTopics() {
    }
}
