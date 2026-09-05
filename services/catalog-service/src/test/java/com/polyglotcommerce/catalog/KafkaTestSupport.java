package com.polyglotcommerce.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

/**
 * Lettura di un topic Kafka reale (Testcontainers) dentro i test: il
 * contratto fra servizi e' il JSON sul topic, quindi si usa il client
 * Kafka nudo invece di passare da Spring.
 */
final class KafkaTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KafkaTestSupport() {
    }

    /**
     * Attende sul topic l'evento che riguarda il prodotto indicato,
     * ignorando quelli lasciati da altri test.
     */
    static JsonNode awaitProductEvent(String bootstrapServers, String topic, long productId, Duration timeout) {
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
                    if (envelope.path("data").path("productId").asLong() == productId) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No event for product " + productId + " on topic " + topic
                + " within " + timeout);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON on topic: " + json, e);
        }
    }
}
