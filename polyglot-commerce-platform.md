# Polyglot Commerce Platform

> Piattaforma e-commerce distribuita basata su architettura a
> microservizi, tecnologie polyglot, event-driven architecture e
> Kubernetes.

## 1. Overview

**Polyglot Commerce Platform** è un progetto dimostrativo progettato per
implementare un'architettura moderna a microservizi utilizzando
linguaggi, framework e pattern differenti.

L'obiettivo non è solamente realizzare un e-commerce, ma costruire un
ambiente realistico nel quale sperimentare:

-   Microservices Architecture
-   Polyglot Programming
-   REST API
-   Event-Driven Architecture
-   Apache Kafka
-   Apache Camel
-   API Gateway
-   Kubernetes
-   Docker
-   OAuth2 / OpenID Connect
-   CI/CD
-   Distributed Tracing
-   Centralized Logging
-   Metrics & Monitoring
-   Resilience Patterns
-   Saga Pattern
-   Contract Testing

## 2. Obiettivi

Ogni microservizio: - possiede una responsabilità specifica; - può
essere sviluppato con una tecnologia differente; - possiede il proprio
database; - viene distribuito indipendentemente; - espone API REST
quando necessario; - può comunicare tramite eventi Kafka; - viene
eseguito tramite container Docker; - può essere distribuito su
Kubernetes; - espone metriche, log e tracing.

## 3. High-Level Architecture

``` text
                              Internet
                                 |
                                 v
                        +------------------+
                        | Kubernetes       |
                        | API Gateway      |
                        +--------+---------+
                                 |
              +------------------+------------------+
              |                  |                  |
              v                  v                  v
        Customer Web         Admin Web          REST APIs
          Angular              React
                                 |
    +-----------------------------------------------------------+
    |                       Microservices                       |
    |  Auth          Catalog         Order          Payment     |
    |  Spring Boot   Spring Boot     Spring Boot    Spring Boot |
    |                                + Kafka         + Kafka     |
    |                                                           |
    |  Inventory          Integration Service   Analytics       |
    |  Python / FastAPI   Apache Camel           Python/FastAPI |
    +-----------------------------------------------------------+
```

## 4. Technology Stack

  Componente             Tecnologia                   Responsabilità
  ---------------------- ---------------------------- -----------------------------------
  Customer Web           Angular + TypeScript         Applicazione cliente
  Admin Web              React + TypeScript           Dashboard amministrativa
  Auth Service           Spring Boot                  Utenti, ruoli e integrazione OIDC
  Catalog Service        Spring Boot                  Prodotti e categorie
  Order Service          Spring Boot + Kafka          Gestione ordini
  Payment Service        Spring Boot + Kafka          Pagamenti e transazioni
  Inventory Service      Python + FastAPI             Magazzino
  Integration Service    Spring Boot + Apache Camel   Integrazioni
  Notification Service   Spring Boot + Camel          Notifiche
  Analytics Service      Python + FastAPI             Analytics
  Object Storage         MinIO (S3-compatibile)       Immagini dei prodotti

## 5. Backend Services

### Auth Service

**Stack:** Java, Spring Boot, Spring Security, OAuth2/OIDC, PostgreSQL.

Responsabilità: profili utenti, ruoli, autorizzazioni applicative e
integrazione con Keycloak.

*Stato*: non implementato. Identità, credenziali e ruoli sono gestiti
direttamente da Keycloak (che usa `auth-db` come proprio database), e
ogni servizio applica da sé le proprie autorizzazioni leggendo i ruoli
dal token. Questo servizio serve quando ci saranno dati di profilo
applicativi da custodire — indirizzi di spedizione, preferenze,
consensi — che non hanno senso dentro l'identity provider.

### Catalog Service

**Stack:** Java, Spring Boot, Spring Data JPA, PostgreSQL.

API principali:

``` http
GET /api/products
GET /api/products/{id}
POST /api/products
PUT /api/products/{id}
DELETE /api/products/{id}
GET /api/categories

POST /api/products/{id}/image
GET  /api/products/{id}/image
```

Un prodotto puo' avere l'immagine come **indirizzo esterno** (`imageUrl`
con un URL assoluto) oppure come **file caricato**: in quel caso il
binario va su un object storage S3-compatibile (MinIO in locale) e
`imageUrl` diventa `/api/products/{id}/image`, servito dal servizio
stesso.

Nel database resta solo il riferimento, mai i byte: un file per riga
appesantirebbe ogni backup e ogni lettura della tabella prodotti, e
un'immagine non ha nulla di transazionale. Il percorso salvato e'
**relativo** e non l'indirizzo dell'object storage, che e' diverso fra
locale e cluster e resterebbe congelato nelle righe gia' scritte --
lo stesso inciampo dell'issuer di Keycloak, qui evitato per
costruzione. Dettagli in
`infrastructure/minio/README.md`.

Pubblica `product.created` alla creazione di un prodotto. Serve
all'Inventory Service, che apre la riga di magazzino corrispondente:
senza, un prodotto nuovo resterebbe sconosciuto al magazzino e ogni
ordine che lo contiene verrebbe rifiutato e annullato dalla saga.

I prodotti inseriti direttamente nel database (`data.sql`) non passano
dal servizio e quindi non generano l'evento: le loro scorte si
dichiarano con lo script `infrastructure/demo/seed-stock.sh`.

### Order Service

**Stack:** Java, Spring Boot, Spring Data JPA, Kafka, PostgreSQL.

``` http
POST /api/orders
GET /api/orders/{id}
GET /api/orders
PATCH /api/orders/{id}/status
```

### Payment Service

**Stack:** Java, Spring Boot, Spring Data JPA, PostgreSQL, Kafka.

``` http
POST /api/payments
GET /api/payments/{id}
```

Consuma `payment.requested` (emesso durante l'orchestrazione della
saga) ed esegue l'addebito verso il provider di pagamento configurato,
pubblicando `payment.completed` o `payment.failed`.

### Inventory Service

**Stack:** Python, FastAPI, SQLAlchemy, PostgreSQL, Kafka.

``` http
GET /api/inventory/{productId}
PUT /api/inventory/{productId}
POST /api/inventory/reservations
DELETE /api/inventory/reservations/{id}
```

`PUT /api/inventory/{productId}` e' il rifornimento: dichiara quante
unita' sono disponibili (riservato ai ruoli WAREHOUSE e ADMIN). Si
dichiara il totale e non una variazione, come fa chi conta cio' che ha
sullo scaffale; le unita' gia' riservate da ordini in corso restano
intatte. E' l'unico modo di aumentare le scorte: la saga sa solo
riservare e rilasciare. Da Admin Web e' il campo "Scorte disponibili"
del form prodotto; l'elenco segnala i prodotti a zero, che sono in
vetrina ma non ordinabili.

Eventi:

``` text
product.created
order.created
order.cancelled
inventory.reserved
inventory.rejected
inventory.released
```

Consumando `product.created` crea la riga di magazzino di un prodotto
nuovo, **a zero disponibili**: un prodotto appena messo a catalogo non
ha pezzi finche' non arrivano davvero. La riga serve comunque subito,
perche' distingue "prodotto senza scorte" (rifiuto legittimo, con un
motivo comprensibile) da "prodotto che il magazzino non conosce
affatto".

### Integration Service

**Stack:** Java, Spring Boot, Apache Camel, Kafka.

Responsabilità: routing, trasformazione messaggi, retry, gestione errori
e integrazione con sistemi esterni.

Agisce inoltre come **orchestratore della saga** Order → Inventory →
Payment (vedi [Saga Orchestration](#saga-orchestration)): coordina la
sequenza degli step, ne verifica l'esito e attiva le compensazioni
necessarie in caso di fallimento.

### Notification Service

**Stack:** Java, Spring Boot, Apache Camel, Kafka.

Consuma eventi relativi a ordini, pagamenti e spedizioni e genera
notifiche.

### Analytics Service

**Stack:** Python, FastAPI, Kafka, MongoDB.

Raccoglie e aggrega eventi per dashboard e statistiche.

## 6. Database per Service

Ogni servizio è proprietario dei propri dati.

``` text
Auth Service       -> auth-db
Catalog Service    -> catalog-db
Order Service      -> orders-db
Payment Service    -> payments-db
Inventory Service  -> inventory-db
Analytics Service  -> analytics-db
```

Un servizio non deve accedere direttamente alle tabelle di un altro
servizio.

## 7. API Gateway

Routing previsto:

``` text
/api/auth/**       -> auth-service
/api/products/**   -> catalog-service
/api/categories/** -> catalog-service
/api/orders/**     -> order-service
/api/payments/**   -> payment-service
/api/inventory/**  -> inventory-service
/api/analytics/**  -> analytics-service
```

Il gateway gestirà progressivamente TLS, CORS, rate limiting,
autenticazione, traffic splitting e API versioning.

## 8. Event-Driven Architecture

Apache Kafka rappresenta l'event backbone.

``` text
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
```

A questi si aggiunge un topic tecnico, `saga.dlq`, dove finiscono i
messaggi che un consumer non riesce a processare dopo i tentativi
previsti: senza, un evento malformato bloccherebbe la partizione
riproponendosi all'infinito.

### Event Envelope

``` json
{
  "eventId": "uuid",
  "eventType": "ORDER_CREATED",
  "eventVersion": 1,
  "timestamp": "2026-08-28T10:30:00Z",
  "correlationId": "uuid",
  "source": "order-service",
  "data": {}
}
```

### Saga Orchestration

Il flusso Order → Inventory → Payment è gestito come **saga
orchestrata** dall'Integration Service (Apache Camel), non come pura
coreografia:

1. L'Order Service pubblica `order.created`. L'Integration Service lo
   consuma e apre la saga, conservandone lo stato (importo da
   addebitare e `correlationId`, propagato poi a tutti gli eventi
   successivi).
2. L'Inventory Service consuma lo stesso `order.created`, prova a
   riservare le scorte di tutti gli item (tutto-o-niente) e pubblica
   `inventory.reserved` oppure `inventory.rejected`.
3. Se la riserva ha successo, l'Integration Service richiede il
   pagamento pubblicando `payment.requested` e attende
   `payment.completed` / `payment.failed` dal Payment Service.
4. In base all'esito complessivo pubblica `order.updated` (successo) o
   `order.cancelled` (fallimento); l'Order Service consuma questi
   eventi e allinea lo stato dell'ordine.
5. **Compensazione**: `order.cancelled` è anche il segnale che la
   innesca — l'Inventory Service lo consuma e rilascia le prenotazioni
   di quell'ordine, pubblicando `inventory.released`. Il rimborso non
   serve nel percorso attuale, perché un pagamento rifiutato non
   addebita nulla; servirebbe per fallimenti successivi all'addebito.

I servizi di dominio comunicano quindi solo per eventi e non si
conoscono tra loro: è l'Integration Service a decidere quale passo
segue quale, e quando compensare.

**Motivo dell'annullamento.** `order.cancelled` porta due campi: un
`reasonCode` (`INVENTORY_REJECTED`, `PAYMENT_FAILED`,
`SAGA_STATE_LOST`) e un `reason` in testo libero. Il codice è il
contratto — l'Order Service lo registra sull'ordine e il checkout ci
sceglie il messaggio da mostrare al cliente — mentre il testo serve a
chi legge i log e può essere riformulato senza rompere nulla.

Senza questa distinzione il cliente vedrebbe solo "ordine annullato",
mentre scorte esaurite e pagamento rifiutato hanno rimedi opposti:
togliere un articolo dal carrello nel primo caso, riprovare il
pagamento nel secondo.

Nota sullo stato: l'orchestratore tiene le saghe in corso in memoria.
Al suo riavvio quelle ancora aperte perdono il contesto, e un
`inventory.reserved` senza stato corrispondente viene trattato come
fallimento (ordine annullato, scorte rilasciate) invece di lasciare
ordine e scorte bloccati. Uno store persistente è l'evoluzione
naturale.

## 9. Authentication & Authorization

Identity Provider: **Keycloak**

Protocolli: - OAuth 2.0 - OpenID Connect - JWT

Ruoli: - CUSTOMER - ADMIN - WAREHOUSE - SUPPORT

Comunicazione service-to-service: i servizi interni si autenticano tra
loro tramite **OAuth2 Client Credentials Grant** verso Keycloak,
ottenendo un token applicativo dedicato (distinto dal token utente)
per ogni chiamata sincrona tra microservizi.

I frontend sono **client pubblici con PKCE**: girano nel browser e non
possono custodire un segreto. I servizi sono *resource server*: non
vedono mai le credenziali, verificano la firma del token con le chiavi
pubbliche del realm (JWKS) e leggono i ruoli da `realm_access.roles`.

Autorizzazioni per servizio:

| Operazione | Chi |
|---|---|
| Sfogliare catalogo e categorie | chiunque, anche senza login |
| Modificare il catalogo | ADMIN |
| Creare e rileggere i propri ordini | CUSTOMER |
| Vedere gli ordini di tutti | ADMIN, SUPPORT |
| Cambiare a mano lo stato di un ordine | ADMIN |
| Creare pagamenti da back office | ADMIN |
| Leggere i pagamenti | ADMIN, SUPPORT |
| Leggere le scorte | qualunque utente autenticato |
| Muovere scorte e prenotazioni | WAREHOUSE, ADMIN |

Un ordine e' intestato a chi presenta il token: l'email non viaggia piu'
nella richiesta, e un cliente che chiede l'ordine di un altro riceve 403
(non 404: l'ordine esiste, semplicemente non e' suo).

L'identita' e' il claim `sub` del token, non l'email. L'email e' un
attributo modificabile e dichiarabile da chiunque in fase di
registrazione: legarci la proprieta' dei dati significherebbe che
registrarsi con l'indirizzo di un'altra persona basta per vederne gli
ordini. L'email resta memorizzata per mostrarla e per le notifiche.

Nota sul confine: questi controlli valgono sulle API HTTP. I passi della
saga viaggiano su Kafka e non attraversano i filtri di sicurezza HTTP;
il broker e' considerato rete interna. Proteggerlo (SASL/mTLS) e' un
tema a se'.

## 10. Docker e Kubernetes

Ogni applicazione possiede il proprio `Dockerfile`.

In locale l'infrastruttura potrà essere eseguita con Docker Compose.

Kubernetes utilizzerà: - Deployment - Service - ConfigMap - Secret -
HorizontalPodAutoscaler - GatewayClass - Gateway - HTTPRoute

Struttura:

``` text
infrastructure/helm/polyglot-commerce/
├── Chart.yaml
├── values.yaml              # default: tutto dentro il cluster
├── values-local.yaml        # dipendenze su docker-compose (host.docker.internal)
├── values-production.yaml   # segnaposto: cosa manca prima di deployare
└── templates/
    ├── _helpers.tpl
    ├── namespace.yaml
    ├── configmap.yaml       # un template solo, che cicla sui servizi
    ├── secret.yaml
    ├── deployment.yaml
    ├── service.yaml
    ├── httproute.yaml
    └── gateway.yaml
```

I servizi sono definiti in `values.yaml` come voci di una mappa
(porta, probe, rotte, database), e i template ci ciclano sopra: aggiungere
un microservizio significa aggiungere una decina di righe di
configurazione, non una cartella di manifest.

## 11. Observability

Tre pilastri: - Logs - Metrics - Traces

Stack: - Prometheus - Grafana - Loki - Tempo - OpenTelemetry

Ogni richiesta dovrà essere correlabile tramite `traceId`, `spanId` e
`correlationId`.

## 12. Resilience

Pattern previsti: - Timeout - Retry - Circuit Breaker - Bulkhead - Dead
Letter Queue - Idempotent Consumer - Saga Pattern

Il Saga Pattern è implementato in modalità **orchestrata**
dall'Integration Service (Apache Camel) — vedi [Saga
Orchestration](#saga-orchestration).

## 13. Testing Strategy

Java: - JUnit - Mockito - Testcontainers

Python: - pytest - Testcontainers

Frontend: - Angular Testing - React Testing Library - Playwright

API: - OpenAPI 3 - Swagger UI

Contract Testing: - Pact, per verificare la compatibilità dei contratti
tra i servizi produttori/consumatori di API ed eventi (in particolare
tra Order/Payment/Inventory nel flusso di saga).

## 14. CI/CD

GitHub Actions:

``` text
Git Push
   |
   v
Lint
   |
Unit Tests
   |
Integration Tests
   |
Security Scan
   |
Build
   |
Docker Build
   |
Container Registry
   |
Kubernetes
```

Le pipeline useranno path filtering per compilare solamente i componenti
modificati.

## 15. Repository Structure

``` text
polyglot-commerce-platform/
├── README.md
├── docs/
│   ├── architecture/
│   ├── api/
│   ├── events/
│   └── adr/
├── frontend/
│   ├── customer-web/
│   └── admin-web/
├── services/
│   ├── auth-service/
│   ├── catalog-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── inventory-service/
│   ├── analytics-service/
│   ├── integration-service/
│   └── notification-service/
├── infrastructure/
│   ├── kubernetes/
│   ├── kafka/
│   ├── gateway/
│   ├── monitoring/
│   └── keycloak/
├── helm/
├── scripts/
├── docker-compose.yml
└── .github/
    └── workflows/
```

## 16. Architecture Decision Records

Gli ADR saranno salvati in `docs/adr/`.

Esempi:

``` text
0001-use-monorepo.md
0002-use-kafka.md
0003-database-per-service.md
0004-use-kubernetes-gateway-api.md
0005-use-keycloak.md
0006-use-apache-camel.md
0007-saga-orchestration-with-camel.md
0008-service-to-service-auth.md
```

## 17. Roadmap

### Phase 1 --- Foundation

-   [ ] Creazione monorepo
-   [ ] Angular customer application
-   [ ] Catalog Service Spring Boot
-   [ ] PostgreSQL
-   [ ] Docker / Docker Compose
-   [ ] API Gateway
-   [ ] Kubernetes base
-   [ ] OpenAPI

### Phase 2 --- Orders & Inventory

-   [ ] Order Service Spring Boot
-   [ ] Payment Service Spring Boot
-   [ ] Inventory Service Python/FastAPI
-   [ ] Database separati
-   [ ] Integration tests
-   [ ] Testcontainers

### Phase 3 --- Event-Driven Architecture

-   [ ] Kafka
-   [ ] Event envelope
-   [ ] Apache Camel Integration Service
-   [ ] Saga orchestration via Apache Camel (Order → Inventory →
    Payment)
-   [ ] Compensating transactions
-   [ ] Retry
-   [ ] DLQ
-   [ ] Idempotent consumers

### Phase 4 --- Security & Administration

-   [ ] React Admin
-   [ ] Keycloak
-   [ ] OAuth2/OIDC
-   [ ] JWT
-   [ ] Authorization

### Phase 5 --- Observability

-   [ ] Prometheus
-   [ ] Grafana
-   [ ] Loki
-   [ ] Tempo
-   [ ] OpenTelemetry
-   [ ] Alerting

### Phase 6 --- Advanced Architecture

-   [ ] Saga Pattern
-   [ ] Circuit Breaker
-   [ ] Contract Testing
-   [ ] Horizontal Pod Autoscaling
-   [ ] Kubernetes probes
-   [ ] Network Policies
-   [ ] Chaos testing
-   [ ] Performance testing

## 18. Definition of Done

Un microservizio è completo quando dispone di: - \[ \] API/consumer
definiti - \[ \] Unit test - \[ \] Integration test - \[ \] OpenAPI per
REST - \[ \] Dockerfile - \[ \] Health/readiness checks - \[ \]
Metrics - \[ \] Structured logging - \[ \] Tracing - \[ \] Kubernetes
Deployment e Service - \[ \] ConfigMap/Secret - \[ \] CI pipeline - \[
\] Error handling - \[ \] README

## 19. Engineering Principles

-   **Independent Deployment**
-   **Database per Service**
-   **API First**
-   **Event First**
-   **Observability by Default**
-   **Infrastructure as Code**
-   **Security by Design**

## 20. Future Improvements

-   Service Mesh
-   GitOps
-   Argo CD
-   Schema Registry
-   Avro / Protobuf
-   Redis
-   Search Engine
-   Feature Flags
-   Blue/Green Deployment
-   Canary Deployment
-   Cloud Deployment
-   Chaos Engineering
-   Secret Vault
-   Automated Load Testing

## 21. Project Goal

L'obiettivo finale è costruire un progetto completo che permetta di
studiare e dimostrare concretamente:

**Java + Spring Boot + Apache Camel + Python + FastAPI + Angular +
React + Kafka + PostgreSQL + MongoDB + Docker + Kubernetes + Gateway
API + Keycloak + OAuth2/OIDC + OpenTelemetry + Prometheus + Grafana +
CI/CD.**

Ogni tecnologia deve avere una responsabilità e una motivazione
architetturale chiara.
