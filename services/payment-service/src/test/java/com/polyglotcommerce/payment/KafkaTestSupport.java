package com.polyglotcommerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

/**
 * Piccole utility per leggere e scrivere sui topic di un broker Kafka reale
 * (Testcontainers) dentro i test: i servizi comunicano via JSON, quindi qui
 * si usano i client Kafka nudi, senza passare da Spring.
 */
final class KafkaTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KafkaTestSupport() {
    }

    static void send(String bootstrapServers, String topic, String key, String json) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, json));
            producer.flush();
        }
    }

    /**
     * Attende sul topic un evento che riguardi l'ordine indicato, ignorando
     * quelli di altri test rimasti sullo stesso topic.
     */
    static JsonNode awaitEvent(String bootstrapServers, String topic, long orderId, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        Instant deadline = Instant.now().plus(timeout);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    JsonNode envelope = parse(record.value());
                    if (envelope.path("data").path("orderId").asLong() == orderId) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No event for order " + orderId + " on topic " + topic
                + " within " + timeout);
    }

    /** Come {@link #awaitEvent}, ma attende di vederne un numero preciso. */
    static java.util.List<JsonNode> awaitEvents(String bootstrapServers, String topic, long orderId,
                                                int expected, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        java.util.List<JsonNode> matching = new java.util.ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            while (Instant.now().isBefore(deadline) && matching.size() < expected) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = parse(record.value());
                    if (envelope.path("data").path("orderId").asLong() == orderId) {
                        matching.add(envelope);
                    }
                }
            }
        }
        if (matching.size() < expected) {
            throw new AssertionError("Expected " + expected + " events for order " + orderId + " on topic "
                    + topic + ", got " + matching.size() + " within " + timeout);
        }
        return matching;
    }

    static String envelope(String eventType, String correlationId, String dataJson) {
        return "{"
                + "\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"eventType\":\"" + eventType + "\","
                + "\"eventVersion\":1,"
                + "\"timestamp\":\"" + Instant.now() + "\","
                + "\"correlationId\":\"" + correlationId + "\","
                + "\"source\":\"test\","
                + "\"data\":" + dataJson
                + "}";
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON on topic: " + json, e);
        }
    }
}
