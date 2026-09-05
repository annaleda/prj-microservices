package com.polyglotcommerce.order.config;

import com.polyglotcommerce.order.event.EventTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /**
     * Retry e DLQ per i consumer: tre tentativi a un secondo di distanza e,
     * se il messaggio continua a fallire, spostamento su {@code saga.dlq}
     * invece del blocco della partizione (comportamento di default: il
     * container riprova all'infinito lo stesso record).
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(EventTopics.SAGA_DLQ, -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
    }
}
