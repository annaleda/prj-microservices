# Polyglot Commerce Platform

Guida rapida per avviare in locale ciò che è stato implementato finora.
Per l'architettura completa vedi
[polyglot-commerce-platform.md](polyglot-commerce-platform.md).

---

## 1. Avviare il Catalog Service in locale

Microservizi implementati finora:

| Servizio | Stack | Porta | Cartella |
|---|---|---|---|
| Catalog Service | Spring Boot 2.7 / Java 11 | 8081 | `services/catalog-service/` |
| Order Service | Spring Boot 2.7 / Java 11 | 8082 | `services/order-service/` |
| Inventory Service | Python / FastAPI | 8083 | `services/inventory-service/` |
| Payment Service | Spring Boot 2.7 / Java 11 | 8084 | `services/payment-service/` |
| Integration Service (saga) | Spring Boot 2.7 + Apache Camel | 8085 | `services/integration-service/` |

Questa sezione parte da **Catalog Service**, il piu' semplice (nessun
evento, solo REST + database); per il flusso a eventi vedi la
[sezione 3](#3-provare-la-saga-order---inventory---payment).

### Prerequisiti

- Docker Desktop (per il database)
- Java 11 e Maven 3.8+

### 1.1 Avviare il database

Dalla root del progetto:

```bash
docker compose up -d catalog-db
docker compose ps
```

`catalog-db` deve risultare `healthy`. Le credenziali/porte sono in
[.env](.env) (`localhost:5434`, db `catalog`, utente/password
`catalog`/`catalog`).

> Per avviare **tutti** i database del progetto (utile se in futuro si
> lavora anche su altri servizi) basta omettere il nome del servizio:
> `docker compose up -d`.

### 1.2 Avviare Catalog Service

```bash
cd services/catalog-service
mvn -s .mvn/settings.xml spring-boot:run
```

> Il flag `-s .mvn/settings.xml` è necessario perché il Maven di questa
> macchina è configurato di default su repository interni aziendali,
> raggiungibili solo in VPN. Il file `.mvn/settings.xml`, locale al
> progetto, punta direttamente a Maven Central senza toccare la
> configurazione condivisa della macchina.

Il servizio parte su `http://localhost:8081`.

### 1.3 Verificare che funzioni

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/api/categories
curl http://localhost:8081/api/products
```

Documentazione interattiva delle API (Swagger UI):
`http://localhost:8081/swagger-ui.html`.

### 1.4 (Facoltativo) Avviare il frontend Customer Web

Il frontend Angular (`frontend/customer-web/`) sfoglia il catalogo
esposto da Catalog Service:

```bash
cd frontend/customer-web
npm install
npm start
```

Apri `http://localhost:4200`. Le chiamate `/api/...` vengono
inoltrate automaticamente a `http://localhost:8081` tramite
`proxy.conf.json` (nessuna configurazione aggiuntiva necessaria).

> Anche qui, `npm install` usa il registry pubblico grazie al
> `.npmrc` locale al progetto (il registry npm di default della
> macchina è quello interno aziendale, non raggiungibile da questa
> rete).

---

## 2. Avviare Kafka in locale

Il broker Kafka (modalità KRaft, senza Zookeeper) e la UI di
ispezione fanno parte di `docker-compose.yml`:

```bash
docker compose up -d kafka kafka-init kafka-ui
```

- `kafka`: broker, raggiungibile dagli altri container come
  `kafka:9092`, dall'host come `localhost:9094`.
- `kafka-init`: container "one-shot" che crea i topic dell'event
  catalog (vedi [polyglot-commerce-platform.md](polyglot-commerce-platform.md),
  sezione 8) al primo avvio, poi termina — è normale vederlo in stato
  `exited (0)`.
- `kafka-ui`: interfaccia web per ispezionare topic e messaggi —
  `http://localhost:8090`.

> Order, Inventory, Payment e Integration Service pubblicano e
> consumano questi topic: è su di essi che gira la saga descritta
> nella [sezione 3](#3-provare-la-saga-order---inventory---payment).
> Dettagli in [infrastructure/kafka/README.md](infrastructure/kafka/README.md).

## 3. Provare la saga Order -> Inventory -> Payment

E' il flusso a eventi del progetto. L'unica azione richiesta e' creare
un ordine: riserva delle scorte, pagamento ed esito finale avvengono
da soli, coordinati dall'**Integration Service** (Apache Camel) via
Kafka. Nessuno dei tre servizi di dominio chiama gli altri.

```text
order.created ---> Inventory Service ---> inventory.reserved --+
      |                                   inventory.rejected   |
      v                                                        v
Integration Service (saga) <---------------------------- payment.requested
      |                                                        |
      +--> order.updated (CONFIRMED)   <--- payment.completed --+
      +--> order.cancelled (+ rilascio scorte) <--- payment.failed
```

### 3.1 Avviare infrastruttura e servizi

Dalla radice del repository:

```bash
docker compose up -d          # database + Kafka + kafka-ui
```

Poi, in quattro terminali distinti:

```bash
cd services/order-service       && mvn -s .mvn/settings.xml spring-boot:run   # 8082
cd services/inventory-service   && uvicorn app.main:app --port 8083           # 8083
cd services/payment-service     && mvn -s .mvn/settings.xml spring-boot:run   # 8084
cd services/integration-service && mvn -s .mvn/settings.xml spring-boot:run   # 8085
```

> L'Inventory Service richiede le dipendenze Python installate
> (`pip install -r requirements.txt`, preferibilmente in un
> virtualenv). Il suo consumer Kafka si puo' spegnere con
> `EVENTS_ENABLED=false` per usarlo come sola API REST.

### 3.2 Percorso felice: ordine confermato

```bash
curl -s http://localhost:8083/api/inventory/1          # scorte prima

curl -s -X POST http://localhost:8082/api/orders   -H "Content-Type: application/json"   -d '{"customerEmail":"demo@example.com","items":[{"productId":1,"productName":"Wireless Mouse","quantity":2,"unitPrice":29.90}]}'

# dopo un paio di secondi (l'id e' quello restituito sopra)
curl -s http://localhost:8082/api/orders/1             # status: CONFIRMED
curl -s http://localhost:8083/api/inventory/1          # 2 pezzi riservati
```

### 3.3 Pagamento rifiutato: compensazione

Il gateway di pagamento simulato rifiuta gli importi da 10.000 in su.
L'ordine viene annullato **e le scorte riservate tornano disponibili**:

```bash
curl -s -X POST http://localhost:8082/api/orders   -H "Content-Type: application/json"   -d '{"customerEmail":"demo@example.com","items":[{"productId":2,"productName":"Mechanical Keyboard","quantity":40,"unitPrice":250.00}]}'

# status: CANCELLED, e le scorte del prodotto 2 sono di nuovo quelle di partenza
curl -s http://localhost:8083/api/inventory/2
```

### 3.4 Scorte insufficienti: saga interrotta subito

```bash
curl -s -X POST http://localhost:8082/api/orders   -H "Content-Type: application/json"   -d '{"customerEmail":"demo@example.com","items":[{"productId":3,"productName":"Rare Book","quantity":9999,"unitPrice":10.00}]}'
```

L'ordine risulta `CANCELLED` senza che venga creato alcun pagamento:
la saga si ferma al rifiuto delle scorte.

### 3.5 Vedere gli eventi

Su `http://localhost:8090` (kafka-ui) si possono ispezionare i topic
uno per uno e leggere gli envelope JSON. Tutti gli eventi di una
stessa saga condividono il `correlationId`, quindi il flusso e'
ricostruibile da un capo all'altro. Il topic `saga.dlq` deve restare
vuoto: se contiene qualcosa, un consumer non e' riuscito a processare
un messaggio nemmeno dopo i tentativi previsti.

### 3.6 Dal browser

Con Customer Web (`npm start` in `frontend/customer-web`, vedi 1.4) il
checkout crea l'ordine e poi ne attende l'esito, mostrando "ordine
confermato" o "ordine annullato" quando la saga si chiude. Serve
anche Catalog Service (8081) per il catalogo.

## 4. Avviare l'infrastruttura Kubernetes con API Gateway

I manifest si trovano in `infrastructure/kubernetes/` (pattern
base/overlays con Kustomize) e usano la **Kubernetes Gateway API**
(non un Ingress classico), implementata con **Envoy Gateway**. Finora
sono stati scritti e validati solo localmente (`kubectl kustomize` +
dry-run), **non ancora testati su un cluster reale** in questa
sessione: i passi seguenti sono la procedura prevista, da verificare
alla prima esecuzione.

### Prerequisiti

- `kubectl`, `kind`, `helm` (già presenti su questa macchina)

### 4.1 Creare un cluster dedicato

Usa un cluster **dedicato al progetto**, non un cluster kind già in
uso per altro:

```bash
kind create cluster --name polyglot-commerce
kubectl config use-context kind-polyglot-commerce
```

### 4.2 Installare Envoy Gateway (il controller della Gateway API)

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.1.0 \
  -n envoy-gateway-system \
  --create-namespace

kubectl wait --timeout=5m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

### 4.3 Costruire e caricare le immagini dei servizi

Il cluster kind non ha accesso automatico alle immagini Docker locali:
vanno costruite e caricate esplicitamente, una per servizio.

```bash
for svc in catalog-service order-service inventory-service payment-service integration-service; do
  docker build -t "polyglot-commerce/$svc:0.1.0" "services/$svc"
  kind load docker-image "polyglot-commerce/$svc:0.1.0" --name polyglot-commerce
done
```

### 4.4 Applicare i manifest (overlay locale)

```bash
kubectl apply -k infrastructure/kubernetes/overlays/local
kubectl -n polyglot-commerce get pods
kubectl -n polyglot-commerce get gateway polyglot-commerce-gateway
```

> **Nota sul database**: nel cluster non c'è (ancora) un Postgres
> in-cluster. L'overlay `local` fa puntare ciascun servizio al proprio
> database avviato via `docker compose` sull'host, raggiungibile da
> dentro kind come `host.docker.internal:<porta>` (funziona su Docker
> Desktop per Windows/Mac; su Linux potrebbe servire una configurazione
> di rete diversa). Assicurati quindi che `docker compose up -d` (tutti
> i database) sia attivo **prima** di applicare questo overlay.

### 4.5 Esporre il Gateway e testare il routing

Envoy Gateway crea un `Service` per il listener HTTP del `Gateway`.
Su kind (senza LoadBalancer reale) si usa `port-forward`:

```bash
export GATEWAY_SERVICE=$(kubectl get svc -n polyglot-commerce \
  -l gateway.envoyproxy.io/owning-gateway-name=polyglot-commerce-gateway \
  -o jsonpath='{.items[0].metadata.name}')

kubectl port-forward -n polyglot-commerce svc/$GATEWAY_SERVICE 8080:80
```

In un altro terminale:

```bash
curl http://localhost:8080/api/products
curl http://localhost:8080/api/categories
curl http://localhost:8080/api/orders
curl http://localhost:8080/api/inventory/1
curl http://localhost:8080/api/payments/1
```

Se tutto funziona, le richieste passano: client → Gateway (Envoy) →
`HTTPRoute` → Service del servizio corrispondente → Pod.

### 4.6 Pulizia

```bash
kind delete cluster --name polyglot-commerce
```

---

## Struttura del repository

Vedi la sezione "Repository Structure" in
[polyglot-commerce-platform.md](polyglot-commerce-platform.md) per la
struttura completa prevista a fine progetto.
