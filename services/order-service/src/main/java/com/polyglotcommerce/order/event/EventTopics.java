package com.polyglotcommerce.order.event;

/** Topic dell'event catalog usati da questo servizio (documento di design, sezione 8). */
public final class EventTopics {

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_UPDATED = "order.updated";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String SAGA_DLQ = "saga.dlq";

    private EventTopics() {
    }
}
