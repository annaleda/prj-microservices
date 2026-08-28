# Kafka (locale, via Docker Compose)

Broker Kafka in modalità **KRaft** (senza Zookeeper), avviato insieme
al resto dell'infrastruttura da `docker-compose.yml` alla radice del
repository:

```bash
docker compose up -d kafka kafka-init kafka-ui
```

- `kafka`: il broker (immagine `apache/kafka`). Raggiungibile dagli
  altri container Docker come `kafka:9092`; dall'host come
  `localhost:9094` (porta configurabile con `KAFKA_HOST_PORT` in
  `.env`).
- `kafka-init`: container "one-shot" che crea i topic dell'event
  catalog (vedi sotto) al primo avvio, poi termina. Non fallisce se i
  topic esistono già.
- `kafka-ui`: interfaccia web per ispezionare topic e messaggi —
  `http://localhost:8090` (porta configurabile con `KAFKA_UI_PORT`).

## Topic

Corrispondono all'event catalog del documento di design (sezione 8):

```
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
```

## Scope di questa fase

Solo l'infrastruttura (broker + topic), **nessun producer/consumer è
stato ancora collegato** nei microservizi esistenti (Order, Inventory,
Payment): quel collegamento è la Saga Orchestration a carico
dell'Integration Service (Apache Camel), non ancora implementato — per
non anticipare logica applicativa senza il componente che la
coordina.
