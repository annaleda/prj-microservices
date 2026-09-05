#!/bin/sh
# Crea i topic dell'event catalog (documento di design, sezione 8) piu'
# saga.dlq, dove finiscono i messaggi che nessun consumer riesce a
# processare dopo i tentativi previsti.
# Idempotente: eseguito una volta ad ogni avvio dello stack, non fallisce
# se i topic esistono gia'.
set -e

BOOTSTRAP="kafka:9092"

TOPICS="
order.created
order.updated
order.cancelled
inventory.reserved
inventory.rejected
inventory.released
inventory.updated
payment.requested
payment.completed
payment.failed
notification.requested
order.shipped
saga.dlq
"

for topic in $TOPICS; do
  echo "Creating topic: $topic"
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists --topic "$topic" \
    --partitions 1 --replication-factor 1
done

echo "All topics ready:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
