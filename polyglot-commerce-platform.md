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
                    +--------------------------+
                    | Kubernetes API Gateway   |      +------------+
                    | (Gateway API + Envoy)    |      |  Keycloak  |
                    +------------+-------------+      |  OIDC      |
                                 |                    +-----+------+
              +------------------+---------------+          |
              |                                  |          | token
              v                                  v          |
        Customer Web                        Admin Web <-----+
          Angular 16                        React + Vite
              |                                  |
              +----------------+-----------------+
                               v
    +-------------------------------------------------------------+
    |                       Microservices                         |
    |                                                             |
    |  Catalog        Order          Payment      Inventory       |
    |  Spring Boot    Spring Boot    Spring Boot  Python/FastAPI  |
    |     |              |              |             |           |
    |     +--------------+--------------+-------------+           |
    |                    |  eventi                                |
    |                    v                                        |
    |            +---------------+      +----------------------+  |
    |            |  Apache Kafka |<---->| Integration Service  |  |
    |            +---------------+      | Camel - saga         |  |
    |                                   +----------------------+  |
    |                                                             |
    |  Auth (previsto)  Notification (previsto)  Analytics (prev.)|
    +-------------------------------------------------------------+
              |                                     |
              v                                     v
      un database per servizio                +-----------+
      (5 Postgres + 1 MongoDB)                |   MinIO   |
                                              | immagini  |
                                              +-----------+
```

I servizi di dominio **non si chiamano fra loro**: comunicano solo per
eventi, ed e' l'Integration Service a coordinare la sequenza.

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
saga) ed esegue l'addebito, pubblicando `payment.completed` o
`payment.failed`.

*Stato*: non esiste alcun gateway di pagamento reale. L'esito e' deciso
da una **regola simulata e dichiarata come tale nel codice** (una soglia
sull'importo) invece di essere sempre positivo, cosi' resta provabile
anche il percorso di fallimento e la relativa compensazione della saga.

Il consumo e' **idempotente sull'ordine**: se un pagamento per
quell'ordine esiste gia' non riaddebita, ma ripubblica l'esito --- chi
lo attendeva potrebbe non aver visto il primo.

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

*Stato*: non implementato. E' la ragione per cui i topic
`notification.requested` e `order.shipped` esistono ma restano senza
consumer e senza traffico. E' anche il servizio mancante piu' piccolo, e
quello che chiuderebbe l'event catalog.

### Analytics Service

**Stack:** Python, FastAPI, Kafka, MongoDB.

Raccoglie e aggrega eventi per dashboard e statistiche.

*Stato*: non implementato. Di conseguenza **`analytics-db` (MongoDB) e'
avviato ma mai usato**, ed e' l'unico pezzo di infrastruttura del
progetto che non serve ancora a nulla; anche il topic
`inventory.updated` resta senza consumer.

## 6. Database per Service

Ogni servizio è proprietario dei propri dati.

``` text
Catalog Service    -> catalog-db      (PostgreSQL)
Order Service      -> orders-db       (PostgreSQL)
Payment Service    -> payments-db     (PostgreSQL)
Inventory Service  -> inventory-db    (PostgreSQL)
Keycloak           -> auth-db         (PostgreSQL)
Analytics Service  -> analytics-db    (MongoDB)   *non ancora usato*
```

Sono **container Postgres distinti**, non schemi diversi della stessa
istanza: la separazione e' voluta e resa visibile.

`auth-db` era previsto per l'Auth Service; poiche' quel servizio non
esiste, oggi lo usa **Keycloak** come proprio database.
`analytics-db` e' avviato ma **mai usato**, perche' l'Analytics Service
non e' implementato.

L'Integration Service **non ha database**: lo stato delle saghe in corso
e' in memoria. E' un limite dichiarato, ed e' il motivo per cui gira a
una sola replica.

Un servizio non deve accedere direttamente alle tabelle di un altro
servizio. Conseguenza pratica accettata: **denormalizzazione voluta** ---
una riga d'ordine conserva nome, prezzo e immagine del prodotto al
momento dell'acquisto, perche' un ordine e' una ricevuta e deve restare
leggibile anche se il prodotto viene tolto dal catalogo.

## 7. API Gateway

Routing previsto:

Rotte attive (un `HTTPRoute` per servizio nel chart Helm):

``` text
/api/products/**   -> catalog-service
/api/categories/** -> catalog-service
/api/orders/**     -> order-service
/api/payments/**   -> payment-service
/api/inventory/**  -> inventory-service
```

Previste, per servizi non ancora implementati:

``` text
/api/auth/**       -> auth-service
/api/analytics/**  -> analytics-service
```

L'**Integration Service non ha rotte**: parla solo per eventi e non
espone API pubbliche. Le immagini dei prodotti sono servite dal Catalog
Service su `/api/products/{id}/image` e ricadono quindi nella sua rotta.

Lo stesso schema di instradamento per prefisso e' replicato nei proxy dei
dev server dei due frontend, cosi' le chiamate usano sempre percorsi
relativi e non servono configurazioni per ambiente.

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

Stack previsto: - Prometheus - Grafana - Loki - Tempo - OpenTelemetry

Ogni richiesta dovrà essere correlabile tramite `traceId`, `spanId` e
`correlationId`.

*Stato*: **nulla di tutto questo e' realizzato** (Phase 5 della roadmap).
Le metriche non sono nemmeno esposte: manca
`micrometer-registry-prometheus`, quindi Actuator pubblica solo
`/actuator/health` e `/actuator/info`.

L'unico pezzo esistente e' il **`correlationId`**, generato all'apertura
di ogni saga e propagato invariato da tutti gli eventi successivi: e'
tracing distribuito fatto a mano, sufficiente a ricostruire un flusso
leggendo i log dei quattro servizi, ma senza visualizzazione ne'
misurazione dei tempi.

Nota su dove il tracing automatico va verificato: il consumer Kafka
dell'Inventory Service gira su un **thread dedicato**, e il contesto di
tracing e' legato al thread. E' esattamente il punto in cui la
propagazione automatica puo' interrompersi.

## 12. Resilience

| Pattern | Stato |
|---|---|
| Retry | **fatto** --- 3 tentativi a un secondo sui consumer Kafka |
| Dead Letter Queue | **fatto** --- topic `saga.dlq` |
| Idempotent Consumer | **fatto** --- in Inventory, Payment e Order |
| Saga Pattern | **fatto** --- orchestrata, vedi [Saga Orchestration](#saga-orchestration) |
| Timeout | parziale --- solo lato frontend, sull'attesa dell'esito della saga |
| Circuit Breaker | **non fatto**, e oggi non avrebbe dove stare: nessun servizio chiama un altro via HTTP |
| Bulkhead | non fatto |

Il Saga Pattern è implementato in modalità **orchestrata**
dall'Integration Service (Apache Camel).

Sul Circuit Breaker vale la pena essere espliciti: e' un pattern per le
chiamate **sincrone** verso qualcosa che puo' degradare. In
un'architettura interamente a eventi non c'e' una chiamata da
interrompere --- il messaggio resta nel broker. Servira' al primo sistema
esterno sincrono: un gateway di pagamento vero, un corriere.

## 13. Testing Strategy

Java: - JUnit - Mockito - Testcontainers

Python: - pytest - Testcontainers

Frontend: - Angular Testing (20 test su Customer Web) - React Testing
Library e Playwright: **non usati**, Admin Web non ha alcun test ed e'
l'unica parte del progetto senza

API: - OpenAPI 3 - Swagger UI

Contract Testing: - Pact, per verificare la compatibilità dei contratti
tra i servizi produttori/consumatori di API ed eventi (in particolare
tra Order/Payment/Inventory nel flusso di saga). **Non implementato.**

*Stato*: i test di integrazione usano **Testcontainers con Postgres,
Kafka e MinIO reali**, non mock. L'unica cosa deliberatamente finta e' la
firma dei token: le regole di autorizzazione testate sono quelle vere,
mentre verificare che Keycloak sappia firmare un JWT non e' compito di
questi test.

Conteggio: Catalog 10, Order 20, Payment 9, Integration 5, Inventory 18,
Customer Web 20.

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

*Stato*: **non implementato**, `.github/workflows/` non esiste. Oggi
test e build si lanciano a mano (vedi la sezione "Test" del README).

## 15. Repository Structure

Stato attuale del repository. Le voci marcate *(previsto)* fanno parte
del progetto ma non esistono ancora.

``` text
polyglot-commerce-platform/
├── README.md
├── docker-compose.yml
├── polyglot-commerce-platform.md      questo documento
├── frontend/
│   ├── customer-web/                  Angular 16
│   └── admin-web/                     React + Vite 4
├── services/
│   ├── catalog-service/               Spring Boot
│   ├── order-service/                 Spring Boot + Kafka
│   ├── payment-service/               Spring Boot + Kafka
│   ├── inventory-service/             Python + FastAPI
│   ├── integration-service/           Spring Boot + Camel (saga)
│   ├── auth-service/                  (previsto)
│   ├── notification-service/          (previsto)
│   └── analytics-service/             (previsto)
├── infrastructure/
│   ├── helm/polyglot-commerce/        chart di deploy (vedi sezione 10)
│   ├── kafka/                         topic, chi produce e chi consuma
│   ├── keycloak/                      realm importabile, tema di login
│   ├── minio/                         object storage delle immagini
│   ├── demo/                          script per i dati dimostrativi
│   └── monitoring/                    (previsto, Phase 5)
├── docs/adr/                          (previsto, vedi sezione 16)
└── .github/workflows/                 (previsto, vedi sezione 14)
```

Rispetto alla struttura immaginata all'inizio, due differenze
consapevoli:

- **il chart Helm sta sotto `infrastructure/`** e non alla radice: e'
  infrastruttura come Kafka e Keycloak, non un componente a se';
- **non c'e' una cartella `gateway/` separata**: `GatewayClass`,
  `Gateway` e gli `HTTPRoute` sono template del chart, perche' vivono e
  muoiono con il deploy dei servizi.

## 16. Architecture Decision Records

Gli ADR saranno salvati in `docs/adr/`.

*Stato*: **nessun ADR scritto**, la cartella non esiste. Le decisioni
elencate qui sotto sono state prese davvero e sono motivate nel diario di
lavoro, ma non sono ancora in forma di ADR.

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

Stato aggiornato: `[x]` fatto e verificato, `[ ]` da fare. Dove la
realizzazione si discosta dal piano c'e' una nota.

### Phase 1 --- Foundation

-   [x] Creazione monorepo
-   [x] Angular customer application
-   [x] Catalog Service Spring Boot
-   [x] PostgreSQL
-   [x] Docker / Docker Compose
-   [x] API Gateway (Gateway API + Envoy; manifest scritti e validati,
    mai applicati a un cluster)
-   [x] Kubernetes base (chart Helm, vedi sezione 10)
-   [x] OpenAPI (Swagger UI su tutti i servizi, con schema di sicurezza
    bearer)

### Phase 2 --- Orders & Inventory

-   [x] Order Service Spring Boot
-   [x] Payment Service Spring Boot
-   [x] Inventory Service Python/FastAPI
-   [x] Database separati
-   [x] Integration tests
-   [x] Testcontainers (Postgres, Kafka e MinIO reali)

### Phase 3 --- Event-Driven Architecture

-   [x] Kafka (KRaft, senza Zookeeper)
-   [x] Event envelope
-   [x] Apache Camel Integration Service
-   [x] Saga orchestration via Apache Camel (Order -> Inventory ->
    Payment)
-   [x] Compensating transactions
-   [x] Retry
-   [x] DLQ (`saga.dlq`)
-   [x] Idempotent consumers

### Phase 4 --- Security & Administration

-   [x] React Admin
-   [x] Keycloak (realm importato da JSON, tema per login e
    registrazione)
-   [x] OAuth2/OIDC (authorization code + PKCE)
-   [x] JWT (verifica con chiavi pubbliche, nessun segreto condiviso)
-   [x] Authorization (ruoli CUSTOMER / ADMIN / WAREHOUSE / SUPPORT)

### Phase 5 --- Observability

**Nessun punto realizzato.** E' l'unica fase interamente da fare, ed e'
anche quella che manca di piu': una saga attraversa quattro servizi e
cinque topic, e oggi per seguirla si leggono i log di quattro processi
correlandoli a mano con il `correlationId`.

-   [ ] Prometheus (le metriche non sono nemmeno esposte: manca
    `micrometer-registry-prometheus`)
-   [ ] Grafana
-   [ ] Loki
-   [ ] Tempo
-   [ ] OpenTelemetry
-   [ ] Alerting

### Phase 6 --- Advanced Architecture

-   [x] Saga Pattern (realizzato in Phase 3)
-   [ ] Circuit Breaker --- **da riconsiderare**: nessun servizio chiama
    un altro via HTTP, comunicano solo per eventi, quindi oggi non
    avrebbe dove stare. Servira' con il primo sistema esterno sincrono
    (un gateway di pagamento vero, un corriere).
-   [ ] Contract Testing (Pact)
-   [ ] Horizontal Pod Autoscaling
-   [x] Kubernetes probes (readiness e liveness su tutti i servizi)
-   [ ] Network Policies
-   [ ] Chaos testing
-   [ ] Performance testing

### Fuori roadmap, realizzato comunque

-   [x] **Object storage** (MinIO) per le immagini dei prodotti, con
    caricamento da Admin Web
-   [x] **Riordino** e dettaglio degli ordini passati in Customer Web
-   [x] **Console ordini** in Admin Web: unico punto in cui si vedono gli
    ordini non riusciti, con cliente, articoli e motivo, per capire cosa
    rifornire

### Non implementato dei servizi previsti

-   **Auth Service** --- rimandato di proposito: identita' e ruoli li
    gestisce Keycloak. Avra' senso con i dati di profilo applicativi
    (indirizzi, preferenze, consensi).
-   **Notification Service** --- da cui i topic `notification.requested`
    e `order.shipped` restano senza consumer.
-   **Analytics Service** --- da cui `analytics-db` (MongoDB) e' avviato
    ma **mai usato**, e il topic `inventory.updated` resta senza
    consumer.

## 18. Definition of Done

Un microservizio è completo quando dispone di:

| Voce | Stato sui cinque servizi realizzati |
|---|---|
| API/consumer definiti | fatto |
| Unit test | parziale --- la copertura e' su test di integrazione, non unitari |
| Integration test | fatto (Testcontainers) |
| OpenAPI per REST | fatto, con schema di sicurezza bearer |
| Dockerfile | fatto |
| Health/readiness checks | fatto |
| ConfigMap/Secret | fatto (chart Helm) |
| Kubernetes Deployment e Service | fatto (scritti e validati, mai applicati) |
| Error handling | fatto |
| **Metrics** | **mancante** su tutti |
| **Structured logging** | **mancante**: log testuali, non JSON |
| **Tracing** | **mancante** |
| **CI pipeline** | **mancante** |
| **README per servizio** | **mancante**: la documentazione e' centralizzata nel README di radice |

**Nessun servizio soddisfa oggi la Definition of Done per intero**: i
quattro punti mancanti sono gli stessi per tutti e cinque, e coincidono
con Phase 5 (observability) e sezione 14 (CI/CD).

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
