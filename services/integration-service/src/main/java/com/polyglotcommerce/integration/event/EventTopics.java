package com.polyglotcommerce.integration.event;

/** Topic dell'event catalog usati dall'orchestratore (documento di design, sezione 8). */
public final class EventTopics {

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_UPDATED = "order.updated";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_REJECTED = "inventory.rejected";
    public static final String PAYMENT_REQUESTED = "payment.requested";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String SAGA_DLQ = "saga.dlq";

    private EventTopics() {
    }
}
