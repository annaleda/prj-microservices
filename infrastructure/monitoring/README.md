# Observability

Metriche, grafici e tracing distribuito, avviati con il resto
dell'infrastruttura:

```bash
docker compose up -d prometheus grafana jaeger
```

| Strumento | Indirizzo | Serve a |
|---|---|---|
| **Prometheus** | `http://localhost:9090` | raccoglie e interroga le metriche |
| **Grafana** | `http://localhost:3000` (`admin`/`admin`) | grafici, con le datasource già configurate |
| **Jaeger** | `http://localhost:16686` | trace distribuite |

## Metriche

Ogni servizio le espone; Prometheus le raccoglie ogni 15 secondi.

| Servizio | Endpoint | Come |
|---|---|---|
| Catalog, Order, Payment, Integration | `/actuator/prometheus` | Micrometer |
| Inventory | `/metrics` | prometheus-fastapi-instrumentator |

Le metriche portano la label `application`, altrimenti in Prometheus i
dati dei cinque servizi si sommerebbero indistinguibili.

Le latenze HTTP sono **istogrammi** con percentili 50/95/99: la media non
serve a niente, e un p99 alto con media buona significa che una richiesta
su cento va male — e quella è di qualcuno.

Qualche query da cui partire:

```promql
# richieste al secondo per servizio
sum by (application) (rate(http_server_requests_seconds_count[1m]))

# latenza al 99° percentile
histogram_quantile(0.99, sum by (le, application) (rate(http_server_requests_seconds_bucket[5m])))

# percentuale di errori
sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum by (application) (rate(http_server_requests_seconds_count[5m]))
```

> **Nota di sicurezza**: `/actuator/prometheus` è raggiungibile senza
> token, perché Prometheus non ne ha uno. Non espone dati sensibili, ma
> in un ambiente reale gli endpoint di management vanno su una **porta
> separata** (`management.server.port`), non instradata dal gateway.

## Tracing

Serve l'agent OpenTelemetry, che si scarica una volta:

```bash
sh infrastructure/monitoring/download-otel-agent.sh
```

Poi i servizi Java si avviano con l'agent — **nessuna modifica al
codice**, li strumenta a runtime:

```bash
AG=infrastructure/monitoring/opentelemetry-javaagent.jar
OTEL="-Dotel.exporter.otlp.endpoint=http://localhost:4318 -Dotel.metrics.exporter=none -Dotel.logs.exporter=none"

java -javaagent:$AG -Dotel.service.name=order-service $OTEL \
     -jar services/order-service/target/order-service.jar
```

Il servizio Python usa l'equivalente:

```bash
cd services/inventory-service
pip install -r requirements-otel.txt

OTEL_SERVICE_NAME=inventory-service \
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
opentelemetry-instrument python -m uvicorn app.main:app --port 8083
```

### Tre trappole, tutte incontrate davvero

**1. La porta sbagliata.** `4318` è OTLP su **HTTP**, `4317` è OTLP su
**gRPC**. Dalla 2.x l'agent Java usa di default `http/protobuf` → va
sulla 4318. Puntandolo alla 4317 l'errore è criptico (*"unexpected end of
stream"*) e le trace semplicemente non arrivano.

**2. I default opposti.** L'SDK **Python** fa il contrario: di default
usa **gRPC**, quindi sulla 4318 fallisce con `StatusCode.UNAVAILABLE`.
Per questo qui il protocollo è dichiarato **esplicitamente** in entrambi
i casi, invece di affidarsi a default che divergono.

**3. `pkg_resources` mancante.** `opentelemetry-instrumentation` lo usa
ancora; dal Python 3.12 i virtualenv non installano `setuptools` di
default, e dalla `setuptools` 81 `pkg_resources` è stato **rimosso**. Da
qui il vincolo `setuptools>=75,<81` in `requirements-otel.txt`.

### Il consumer Python richiede una riga di codice

Nei servizi Java l'agent collega tutto da solo, producer e consumer
compresi. In Python l'auto-strumentazione copre HTTP e SQLAlchemy, ma il
consumer Kafka di questo servizio è **costruito a mano e gira su un
thread dedicato**: la strumentazione di confluent-kafka creava sì uno
span, ma radicato in una trace **nuova**, scollegata dall'ordine.

La soluzione è estrarre il contesto a mano dagli header del messaggio
(`traceparent`, standard W3C Trace Context) — è esattamente il
meccanismo che gli agent automatizzano, vedi
`app/events/consumer.py`. È guardato da un `try/except`: senza le
dipendenze di tracing il servizio funziona lo stesso, perché
l'osservabilità è facoltativa e il servizio no.

## Cosa si vede

Creando un ordine, in Jaeger compare una trace che attraversa **tre
servizi passando da Kafka**:

```
+  0.0ms  order-service        POST /api/orders
+ 25.5ms  order-service        order.created publish
+ 31.1ms  inventory-service    order.created process
+ 31.1ms  integration-service  order.created process
```

Entrambi i consumatori raccolgono lo stesso evento a 31ms dalla
pubblicazione: è la dimostrazione visiva di cosa significa "due consumer
group indipendenti".

### Limite noto: la saga non è una trace sola

Il tratto del pagamento (`payment.requested` → payment-service →
`payment.completed`) finisce in una **trace separata**, perché Camel apre
un nuovo scambio per ogni rotta invece di continuare il contesto in
arrivo.

Quindi la saga completa si legge in due segmenti collegati. Per ricucirli
resta il **`correlationId`**, che tutti gli eventi propagano
nell'envelope — il tracing fatto a mano che c'era già prima, e che qui
continua a servire.

## Cosa manca

- **Loki** (log centralizzati) e **Tempo**: qui il backend delle trace è
  Jaeger, più semplice per un ambiente locale.
- **Un OpenTelemetry Collector** fra i servizi e Jaeger. Non serve — Jaeger
  accetta OTLP direttamente — ma in produzione ci starebbe: è lui a fare
  campionamento, filtri e instradamento verso più backend.
- **Alerting**: nessuna regola definita. La prima da scrivere sarebbe sul
  topic `saga.dlq`, che deve restare vuoto.
- **Dashboard Grafana** preconfezionate: le datasource sono già
  provisionate, i pannelli si costruiscono a mano.
- **Log strutturati**: i servizi loggano in testo, non in JSON, e non
  contengono il `trace_id`. È il pezzo che manca per saltare da un log
  alla sua trace.
