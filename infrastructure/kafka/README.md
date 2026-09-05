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

Corrispondono all'event catalog del documento di design (sezione 8),
piu' il topic tecnico `saga.dlq` (vedi in fondo):

```
product.created
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
```

## Chi produce e chi consuma

| Topic | Producer | Consumer |
|---|---|---|
| `product.created` | Catalog Service | Inventory Service |
| `order.created` | Order Service | Inventory Service, Integration Service |
| `inventory.reserved` / `inventory.rejected` | Inventory Service | Integration Service |
| `payment.requested` | Integration Service | Payment Service |
| `payment.completed` / `payment.failed` | Payment Service | Integration Service |
| `order.updated` / `order.cancelled` | Integration Service | Order Service, Inventory Service (compensazione) |
| `inventory.released` | Inventory Service | — (per ora nessuno) |
| `saga.dlq` | tutti i consumer | — (ispezione manuale) |

`product.created` non fa parte della saga: e' l'annuncio che un
prodotto nuovo esiste, e serve all'Inventory Service per aprirne la
riga di magazzino (a zero disponibili). Senza, un prodotto appena
messo a catalogo risulterebbe sconosciuto al magazzino e ogni ordine
che lo contiene verrebbe rifiutato — con l'ordine annullato dalla saga
e nessun modo evidente di capire perche'.

L'orchestrazione e' descritta nella sezione "Saga Orchestration" del
documento di design; per provarla in locale vedi la sezione 3 del
[README](../../README.md) alla radice del repository.

I topic rimasti senza traffico (`inventory.updated`,
`notification.requested`, `order.shipped`) appartengono a servizi non
ancora implementati (Notification, Analytics): sono creati perche'
fanno parte dell'event catalog del documento di design.

## Dead letter queue

`saga.dlq` non e' un evento di dominio: e' dove finisce un messaggio
che un consumer non riesce a processare dopo i tentativi previsti
(tre, a un secondo di distanza). Senza, un messaggio malformato
bloccherebbe la partizione riproponendosi all'infinito. In condizioni
normali il topic resta vuoto.
