package com.polyglotcommerce.order.service;

import com.polyglotcommerce.order.dto.OrderItemRequest;
import com.polyglotcommerce.order.dto.OrderRequest;
import com.polyglotcommerce.order.dto.OrderResponse;
import com.polyglotcommerce.order.event.EventEnvelope;
import com.polyglotcommerce.order.event.EventTopics;
import com.polyglotcommerce.order.event.OutboundEvent;
import com.polyglotcommerce.order.event.payload.OrderCreatedPayload;
import com.polyglotcommerce.order.exception.InvalidOrderStatusTransitionException;
import com.polyglotcommerce.order.exception.ResourceNotFoundException;
import com.polyglotcommerce.order.model.Order;
import com.polyglotcommerce.order.model.OrderItem;
import com.polyglotcommerce.order.model.OrderStatus;
import com.polyglotcommerce.order.repository.OrderRepository;
import com.polyglotcommerce.order.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Chi e' personale interno vede tutti gli ordini, un cliente solo i
     * propri: il filtro e' sui dati, non sull'URL, quindi non puo' stare
     * nella configurazione di sicurezza.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> findAllFor(Caller caller) {
        List<Order> orders = caller.isStaff()
                ? orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                : orderRepository.findByCustomerIdOrderByCreatedAtDesc(caller.getSubject());

        return orders.stream()
                .map(OrderResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse findByIdFor(Long id, Caller caller) {
        Order order = getOrderOrThrow(id);

        // Il confronto e' sull'identificativo dell'utente: un ordine senza
        // (creato prima dell'autenticazione) non appartiene a nessun
        // account e resta visibile al solo personale interno.
        if (!caller.isStaff() && !caller.getSubject().equals(order.getCustomerId())) {
            // 403 e non 404: l'ordine esiste, semplicemente non e' suo.
            throw new AccessDeniedException("Order " + id + " belongs to another customer");
        }

        return OrderResponse.fromEntity(order);
    }

    @Transactional
    public OrderResponse create(OrderRequest request, Caller caller) {
        Order order = Order.builder()
                .customerId(caller.getSubject())
                .customerEmail(caller.getEmail())
                .status(OrderStatus.CREATED)
                .totalAmount(BigDecimal.ZERO)
                .build();

        for (OrderItemRequest itemRequest : request.getItems()) {
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .productId(itemRequest.getProductId())
                    .productName(itemRequest.getProductName())
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(itemRequest.getUnitPrice())
                    .imageUrl(itemRequest.getImageUrl())
                    .build();
            order.getItems().add(item);
        }
        order.setTotalAmount(computeTotal(order.getItems()));

        Order saved = orderRepository.save(order);
        publishOrderCreated(saved);

        return OrderResponse.fromEntity(saved);
    }

    /**
     * Cambio di stato richiesto esplicitamente via API (PATCH).
     * L'esito della saga arriva invece per evento, vedi
     * {@link #applyStatusFromSaga}.
     */
    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus newStatus) {
        Order order = getOrderOrThrow(id);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStatusTransitionException(
                    "Order " + id + " is already cancelled and its status cannot change");
        }

        order.setStatus(newStatus);
        return OrderResponse.fromEntity(orderRepository.save(order));
    }

    /**
     * Allinea l'ordine all'esito deciso dalla saga (Integration Service).
     *
     * A differenza di {@link #updateStatus} non solleva eccezioni: un
     * consumer Kafka che fallisse su una transizione non valida rimetterebbe
     * in coda lo stesso messaggio all'infinito (o lo manderebbe in DLQ) per
     * una situazione che invece e' del tutto normale, cioe' la ri-consegna
     * di un evento gia' applicato. L'operazione e' quindi idempotente.
     */
    @Transactional
    public void applyStatusFromSaga(Long orderId, OrderStatus newStatus) {
        applyStatusFromSaga(orderId, newStatus, null);
    }

    /**
     * Variante con il motivo dell'esito, valorizzato dalla saga solo quando
     * l'ordine viene annullato: e' cio' che permette al checkout di dire al
     * cliente se il problema erano le scorte o il pagamento, invece del
     * generico "annullato" che non lascia capire nulla.
     */
    @Transactional
    public void applyStatusFromSaga(Long orderId, OrderStatus newStatus, String reasonCode) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Saga outcome for unknown order {}: ignored", orderId);
            return;
        }
        if (order.getStatus() == newStatus) {
            return;
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            log.warn("Order {} is already cancelled: outcome {} ignored", orderId, newStatus);
            return;
        }

        order.setStatus(newStatus);
        if (newStatus == OrderStatus.CANCELLED) {
            order.setCancellationReason(reasonCode);
        }
        orderRepository.save(order);
    }

    /**
     * Elimina un ordine annullato.
     *
     * Solo annullati: un ordine confermato e' la traccia di una vendita
     * avvenuta, con un pagamento e delle scorte impegnate dietro, e
     * cancellarlo significherebbe perdere quella storia. Un ordine
     * annullato non ha lasciato nulla — le scorte sono state rilasciate e
     * il pagamento non e' stato addebitato — quindi togliere di mezzo il
     * residuo e' legittimo.
     *
     * Nessun evento: la saga di quell'ordine si e' gia' conclusa, non c'e'
     * nessuno da avvisare.
     */
    @Transactional
    public void deleteCancelled(Long id) {
        Order order = getOrderOrThrow(id);

        if (order.getStatus() != OrderStatus.CANCELLED) {
            throw new InvalidOrderStatusTransitionException(
                    "Order " + id + " is " + order.getStatus() + ": only cancelled orders can be deleted");
        }

        orderRepository.delete(order);
        log.info("Cancelled order {} deleted", id);
    }

    private void publishOrderCreated(Order order) {
        OrderCreatedPayload payload = OrderCreatedPayload.builder()
                .orderId(order.getId())
                .customerEmail(order.getCustomerEmail())
                .totalAmount(order.getTotalAmount())
                .items(order.getItems().stream()
                        .map(item -> OrderCreatedPayload.Item.builder()
                                .productId(item.getProductId())
                                .quantity(item.getQuantity())
                                .unitPrice(item.getUnitPrice())
                                .build())
                        .collect(Collectors.toList()))
                .build();

        // Un correlationId nuovo per ogni ordine: viene propagato invariato
        // da tutti gli eventi successivi della saga, cosi' l'intero flusso
        // e' ricostruibile dai log dei vari servizi.
        EventEnvelope<OrderCreatedPayload> envelope =
                EventEnvelope.of("ORDER_CREATED", UUID.randomUUID().toString(), payload);

        eventPublisher.publishEvent(
                new OutboundEvent(EventTopics.ORDER_CREATED, String.valueOf(order.getId()), envelope));
    }

    private Order getOrderOrThrow(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    private BigDecimal computeTotal(List<OrderItem> items) {
        return items.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
