package com.polyglotcommerce.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.polyglotcommerce.order.dto.OrderItemRequest;
import com.polyglotcommerce.order.dto.OrderRequest;
import com.polyglotcommerce.order.dto.OrderResponse;
import com.polyglotcommerce.order.dto.OrderStatusUpdateRequest;
import com.polyglotcommerce.order.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.polyglotcommerce.order.TestJwtSupport.ADMIN;
import static com.polyglotcommerce.order.TestJwtSupport.ANONYMOUS;
import static com.polyglotcommerce.order.TestJwtSupport.CUSTOMER;
import static com.polyglotcommerce.order.TestJwtSupport.OTHER_CUSTOMER;
import static com.polyglotcommerce.order.TestJwtSupport.SAME_EMAIL_OTHER_ACCOUNT;
import static com.polyglotcommerce.order.TestJwtSupport.SUPPORT;
import static com.polyglotcommerce.order.TestJwtSupport.as;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
class OrderApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    // Il servizio pubblica order.created e consuma gli esiti della saga:
    // serve un broker vero, non un mock, come gia' per il database.
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

    private OrderRequest sampleOrderRequest() {
        OrderItemRequest item = new OrderItemRequest(
                1L, "Wireless Mouse", 2, new BigDecimal("29.90"), "https://example.com/mouse.jpg");
        return new OrderRequest(List.of(item));
    }

    /** Crea un ordine per conto del cliente indicato dal token. */
    private OrderResponse createOrderAs(String token) {
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/api/orders", HttpMethod.POST, as(token, sampleOrderRequest()), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void createAndReadOrder() {
        OrderResponse created = createOrderAs(CUSTOMER);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.getTotalAmount()).isEqualByComparingTo("59.80");
        assertThat(created.getItems()).hasSize(1);
        // L'intestatario viene dal token, non dalla richiesta.
        assertThat(created.getCustomerEmail()).isEqualTo("customer@example.com");

        ResponseEntity<OrderResponse> getResponse = restTemplate.exchange(
                "/api/orders/" + created.getId(), HttpMethod.GET, as(CUSTOMER), OrderResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getCustomerEmail()).isEqualTo("customer@example.com");

        ResponseEntity<OrderResponse[]> listResponse = restTemplate.exchange(
                "/api/orders", HttpMethod.GET, as(CUSTOMER), OrderResponse[].class);
        assertThat(listResponse.getBody()).extracting(OrderResponse::getId).contains(created.getId());
    }

    @Test
    void getUnknownOrderReturns404() {
        // Il body di errore (ApiError) ha un campo "status" numerico che
        // andrebbe in conflitto con l'enum OrderStatus se deserializzato
        // come OrderResponse: qui interessa solo il codice HTTP.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/orders/999999", HttpMethod.GET, as(CUSTOMER), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateStatusLifecycleAndRejectAfterCancellation() {
        OrderResponse created = createOrderAs(CUSTOMER);

        ResponseEntity<OrderResponse> confirmResponse = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status",
                HttpMethod.PATCH,
                as(ADMIN, new OrderStatusUpdateRequest(OrderStatus.CONFIRMED)),
                OrderResponse.class);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody().getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        ResponseEntity<OrderResponse> cancelResponse = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status",
                HttpMethod.PATCH,
                as(ADMIN, new OrderStatusUpdateRequest(OrderStatus.CANCELLED)),
                OrderResponse.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().getStatus()).isEqualTo(OrderStatus.CANCELLED);

        ResponseEntity<String> rejectedResponse = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status",
                HttpMethod.PATCH,
                as(ADMIN, new OrderStatusUpdateRequest(OrderStatus.CONFIRMED)),
                String.class);
        assertThat(rejectedResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void creatingAnOrderPublishesOrderCreated() {
        OrderResponse created = createOrderAs(CUSTOMER);

        JsonNode envelope = KafkaTestSupport.awaitEvent(
                kafka.getBootstrapServers(), "order.created", created.getId(), Duration.ofSeconds(20));

        assertThat(envelope.path("eventType").asText()).isEqualTo("ORDER_CREATED");
        assertThat(envelope.path("source").asText()).isEqualTo("order-service");
        assertThat(envelope.path("correlationId").asText()).isNotEmpty();

        JsonNode data = envelope.path("data");
        assertThat(data.path("customerEmail").asText()).isEqualTo("customer@example.com");
        assertThat(new BigDecimal(data.path("totalAmount").asText())).isEqualByComparingTo("59.80");
        assertThat(data.path("items")).hasSize(1);
        assertThat(data.path("items").get(0).path("productId").asLong()).isEqualTo(1L);
        assertThat(data.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void sagaOutcomeConfirmsTheOrder() {
        OrderResponse created = createOrderAs(CUSTOMER);

        // L'esito della saga arriva dall'Integration Service: qui lo si
        // simula pubblicando direttamente l'evento sul topic.
        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.updated", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_UPDATED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"status\":\"CONFIRMED\"}"));

        await(() -> readOrder(created.getId())
                .getStatus() == OrderStatus.CONFIRMED);
    }

    @Test
    void sagaOutcomeIsIdempotentOnACancelledOrder() {
        OrderResponse created = createOrderAs(CUSTOMER);

        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"reason\":\"Payment failed\"}"));

        await(() -> readOrder(created.getId())
                .getStatus() == OrderStatus.CANCELLED);

        // Ri-consegna dello stesso evento: nessuna eccezione, nessun cambio
        // di stato (un ordine annullato resta annullato).
        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"reason\":\"Payment failed\"}"));

        assertThat(readOrder(created.getId()).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void anAdminCanDeleteACancelledOrder() {
        OrderResponse created = createOrderAs(CUSTOMER);
        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"reasonCode\":\"INVENTORY_REJECTED\"}"));
        await(() -> readOrder(created.getId()).getStatus() == OrderStatus.CANCELLED);

        assertThat(restTemplate.exchange("/api/orders/" + created.getId(), HttpMethod.DELETE,
                as(ADMIN), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(restTemplate.exchange("/api/orders/" + created.getId(), HttpMethod.GET,
                as(ADMIN), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aConfirmedOrderCannotBeDeleted() {
        // Un ordine confermato e' la traccia di una vendita avvenuta:
        // cancellarlo perderebbe quella storia.
        OrderResponse created = createOrderAs(CUSTOMER);
        restTemplate.exchange("/api/orders/" + created.getId() + "/status", HttpMethod.PATCH,
                as(ADMIN, new OrderStatusUpdateRequest(OrderStatus.CONFIRMED)), OrderResponse.class);

        assertThat(restTemplate.exchange("/api/orders/" + created.getId(), HttpMethod.DELETE,
                as(ADMIN), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(readOrder(created.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void aCustomerCannotDeleteOrders() {
        OrderResponse created = createOrderAs(CUSTOMER);

        assertThat(restTemplate.exchange("/api/orders/" + created.getId(), HttpMethod.DELETE,
                as(CUSTOMER), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/orders/" + created.getId(), HttpMethod.DELETE,
                as(ANONYMOUS), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theProductImageIsKeptWithTheOrder() {
        // L'immagine si conserva nella riga d'ordine come il nome e il
        // prezzo: un ordine e' una ricevuta, e deve restare leggibile anche
        // quando il prodotto viene tolto dal catalogo. Cercarla nel
        // catalogo al momento di mostrarla la faceva sparire.
        OrderResponse created = createOrderAs(CUSTOMER);

        assertThat(created.getItems().get(0).getImageUrl()).isEqualTo("https://example.com/mouse.jpg");
        assertThat(readOrder(created.getId()).getItems().get(0).getImageUrl())
                .isEqualTo("https://example.com/mouse.jpg");
    }

    @Test
    void anItemWithoutAnImageIsAccepted() {
        // Non tutti i prodotti hanno una foto: l'assenza non deve far
        // fallire la creazione dell'ordine.
        OrderRequest request = new OrderRequest(List.of(
                new OrderItemRequest(2L, "Prodotto senza foto", 1, new BigDecimal("5.00"), null)));

        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/api/orders", HttpMethod.POST, as(CUSTOMER, request), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getItems().get(0).getImageUrl()).isNull();
    }

    @Test
    void cancellationReasonFromTheSagaIsStoredAndReturned() {
        // Senza il motivo, il checkout puo' dire solo "annullato": scorte
        // esaurite e pagamento rifiutato sono cause diverse, con rimedi
        // diversi, e il cliente non ha modo di distinguerle.
        OrderResponse created = createOrderAs(CUSTOMER);

        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId()
                                + ",\"reasonCode\":\"INVENTORY_REJECTED\""
                                + ",\"reason\":\"Inventory rejected: no stock for product 10\"}"));

        await(() -> readOrder(created.getId()).getStatus() == OrderStatus.CANCELLED);

        assertThat(readOrder(created.getId()).getCancellationReason())
                .isEqualTo("INVENTORY_REJECTED");
    }

    @Test
    void aCancelledOrderWithoutReasonCodeIsStillCancelled() {
        // Gli eventi pubblicati prima che il codice esistesse non lo hanno:
        // l'ordine deve comunque risultare annullato, senza motivo
        // registrato invece che con uno inventato.
        OrderResponse created = createOrderAs(CUSTOMER);

        KafkaTestSupport.send(kafka.getBootstrapServers(), "order.cancelled", String.valueOf(created.getId()),
                KafkaTestSupport.envelope("ORDER_CANCELLED", "test-correlation",
                        "{\"orderId\":" + created.getId() + ",\"reason\":\"Payment failed\"}"));

        await(() -> readOrder(created.getId()).getStatus() == OrderStatus.CANCELLED);

        assertThat(readOrder(created.getId()).getCancellationReason()).isNull();
    }

    /** Rilegge un ordine come il cliente che lo ha creato. */
    private OrderResponse readOrder(Long id) {
        return restTemplate.exchange("/api/orders/" + id, HttpMethod.GET, as(CUSTOMER), OrderResponse.class)
                .getBody();
    }

    /** Attesa attiva su una condizione che dipende da un consumer asincrono. */
    private void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met within 20s");
    }

    @Test
    void theOrderListStartsFromTheMostRecent() {
        OrderResponse older = createOrderAs(CUSTOMER);
        OrderResponse newer = createOrderAs(CUSTOMER);

        List<OrderResponse> orders = List.of(restTemplate.exchange(
                "/api/orders", HttpMethod.GET, as(CUSTOMER), OrderResponse[].class).getBody());

        // La lista e' uno storico: il piu' recente per primo.
        assertThat(orders).extracting(OrderResponse::getId)
                .containsSubsequence(newer.getId(), older.getId());
    }

    @Test
    void anotherAccountWithTheSameEmailSeesNothing() {
        OrderResponse mine = createOrderAs(CUSTOMER);

        // Stessa email dichiarata, identita' diversa. L'email non e' una
        // prova di identita': chiunque puo' registrarsi dichiarando
        // l'indirizzo di un altro, tanto piu' dove non viene verificata.
        ResponseEntity<OrderResponse[]> list = restTemplate.exchange(
                "/api/orders", HttpMethod.GET, as(SAME_EMAIL_OTHER_ACCOUNT), OrderResponse[].class);
        assertThat(list.getBody()).extracting(OrderResponse::getId).doesNotContain(mine.getId());

        ResponseEntity<String> direct = restTemplate.exchange(
                "/api/orders/" + mine.getId(), HttpMethod.GET, as(SAME_EMAIL_OTHER_ACCOUNT), String.class);
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ordersRequireAuthentication() {
        assertThat(restTemplate.exchange("/api/orders", HttpMethod.GET, as(ANONYMOUS), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/api/orders", HttpMethod.POST, as(ANONYMOUS, sampleOrderRequest()),
                String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aCustomerCannotReadSomeoneElsesOrder() {
        OrderResponse created = createOrderAs(CUSTOMER);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/orders/" + created.getId(), HttpMethod.GET, as(OTHER_CUSTOMER), String.class);

        // 403 e non 404: l'ordine esiste, ma non e' suo.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aCustomerOnlySeesTheirOwnOrders() {
        OrderResponse mine = createOrderAs(CUSTOMER);
        OrderResponse theirs = createOrderAs(OTHER_CUSTOMER);

        List<Long> visibleToOther = List.of(restTemplate.exchange(
                        "/api/orders", HttpMethod.GET, as(OTHER_CUSTOMER), OrderResponse[].class).getBody())
                .stream().map(OrderResponse::getId).collect(java.util.stream.Collectors.toList());

        assertThat(visibleToOther).contains(theirs.getId()).doesNotContain(mine.getId());
    }

    @Test
    void supportSeesEveryOrder() {
        OrderResponse mine = createOrderAs(CUSTOMER);
        OrderResponse theirs = createOrderAs(OTHER_CUSTOMER);

        ResponseEntity<OrderResponse[]> response = restTemplate.exchange(
                "/api/orders", HttpMethod.GET, as(SUPPORT), OrderResponse[].class);

        assertThat(response.getBody()).extracting(OrderResponse::getId)
                .contains(mine.getId(), theirs.getId());
    }

    @Test
    void changingStatusByHandIsReservedToAdmins() {
        OrderResponse created = createOrderAs(CUSTOMER);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status", HttpMethod.PATCH,
                as(CUSTOMER, new OrderStatusUpdateRequest(OrderStatus.CONFIRMED)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
