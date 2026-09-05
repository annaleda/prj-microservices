# Diario di lavoro

Questo file è una cronaca cronologica di ciò che viene fatto sul
progetto **Polyglot Commerce Platform**, sessione per sessione: cosa è
stato creato o modificato, quali decisioni sono state prese e perché.
Non è una guida al codice (per quella vedi
[polyglot-commerce-platform.md](polyglot-commerce-platform.md)), ma il
"registro" delle attività, utile per ritrovare il filo del lavoro tra
una sessione e l'altra.

---

## 2026-08-28

### 1. Design doc: review e chiusura dei gap

Letto e revisionato [polyglot-commerce-platform.md](polyglot-commerce-platform.md).
Individuati e chiusi i seguenti gap:

- **Payment Service** mancante nonostante gli eventi `payment.*` fossero
  già citati: aggiunto come microservizio dedicato (Java/Spring Boot,
  DB proprio `payments-db`). Lo **Shipping Service** invece non è
  stato aggiunto su richiesta esplicita: resta un evento gestito da
  Notification/Integration Service senza microservizio dedicato.
- **Saga Pattern** senza modello di coordinamento esplicito: definita
  come **saga orchestrata dall'Integration Service (Apache Camel)**,
  che coordina Order → Inventory → Payment e gestisce le
  compensazioni (rilascio scorte, rimborso) in caso di fallimento.
  Nuova sezione "Saga Orchestration" nel documento.
- **Contract Testing** citato negli obiettivi ma non nella Testing
  Strategy: aggiunto (Pact).
- **Auth service-to-service** non specificata: aggiunta nota su OAuth2
  Client Credentials Grant via Keycloak.
- Aggiornati di conseguenza: tabella stack, repository structure,
  diagramma architetturale, routing gateway, roadmap, ADR (0007, 0008).

### 2. Infrastruttura database via Docker

Creato [docker-compose.yml](docker-compose.yml) con **un container
Postgres dedicato per ciascun servizio** (auth, catalog, orders,
payments, inventory — scelta esplicita dell'utente a favore
dell'isolamento "database per service" invece di un'unica istanza
multi-database) più **MongoDB** per l'Analytics Service. Ogni
container ha volume persistente, healthcheck e gira sulla rete
`commerce-network`.

Credenziali e porte di sviluppo in [.env](.env) (valori demo, non da
usare in produzione).

Docker Desktop non era in esecuzione: avviato manualmente prima di
`docker compose up -d`. Tutti i 6 container sono stati verificati
`healthy`.

| DB | Porta host | Nome DB |
|---|---|---|
| auth-db | 5433 | auth |
| catalog-db | 5434 | catalog |
| orders-db | 5435 | orders |
| payments-db | 5436 | payments |
| inventory-db | 5437 | inventory |
| analytics-db (Mongo) | 27017 | analytics |

### 3. Catalog Service (primo microservizio, Phase 1 roadmap)

Scaffolding completo in `services/catalog-service/`.

**Decisione tecnica importante**: sulla macchina è installato solo
**Java 11** (Spring Boot 3.x richiede Java 17+). Su scelta
dell'utente, si è optato per **Spring Boot 2.7.18 su Java 11** invece
di installare un JDK più recente — varrà come riferimento anche per
gli altri servizi Java del progetto, finché non si decide di
aggiornare.

**Problema Maven riscontrato e risolto**: il `~/.m2/settings.xml` di
sistema (configurazione aziendale) punta a repository Nexus interni
raggiungibili solo in VPN aziendale, non risolvibili da questa rete.
Anziché modificare la configurazione Maven condivisa della
macchina, è stato creato un file di settings **locale al progetto**
(`services/catalog-service/.mvn/settings.xml`) che punta direttamente
a Maven Central, usato con `mvn -s .mvn/settings.xml ...` (anche nel
Dockerfile). Questo pattern andrà replicato per gli altri servizi Java.

**Contenuto implementato**:
- Entità `Product` e `Category` (JPA), repository Spring Data.
- API REST: `GET/POST/PUT/DELETE /api/products`, `GET /api/categories`,
  come da documento di design.
- Validazione request (`@Valid`), gestione errori centralizzata
  (`ResourceNotFoundException` → 404, validation → 400).
- Seed dati di base (`data.sql`: categorie Electronics/Books/Clothing).
- OpenAPI/Swagger UI (springdoc), Actuator (`/actuator/health`).
- Test di integrazione (`ProductApiIntegrationTest`) con **Testcontainers**
  (Postgres reale, non mockato) che copre il flusso CRUD completo.
- `Dockerfile` multi-stage (build Maven + runtime JRE Alpine).

**Bug reale trovato e corretto durante i test**: `LazyInitializationException`
su `Category` (relazione lazy) perché la mappatura verso il DTO
`ProductResponse` avveniva nel controller, fuori dalla sessione
Hibernate (coerente con `spring.jpa.open-in-view=false`, scelta voluta
per evitare l'anti-pattern Open Session In View). **Fix**: la
mappatura entity → DTO è stata spostata dentro `ProductService`,
dentro il confine transazionale (`@Transactional`), che ora restituisce
DTO invece di entità JPA al controller.

Verificato end-to-end: build e test passano (`mvn -s .mvn/settings.xml test`),
servizio avviato manualmente e provato con richieste HTTP reali contro
`catalog-db` (health check, lista categorie seedate, creazione e
lettura prodotto) — tutto funzionante.

**Prossimo passo naturale (roadmap Phase 2)**: Order Service (Spring
Boot + Kafka) e Payment Service, collegati rispettivamente a
`orders-db` e `payments-db`.

### 4. Kubernetes API Gateway (Gateway API, non Ingress)

Creati i manifest in `infrastructure/kubernetes/` (pattern
base/overlays con Kustomize, come da documento di design):

- `base/gateway/`: `GatewayClass` + `Gateway` per **Envoy Gateway**
  (scelto dall'utente come implementazione della Kubernetes Gateway
  API, coerente con l'ADR 0004 "use-kubernetes-gateway-api"). Il
  controller stesso non è incluso: è infrastruttura di cluster, non
  applicativa — istruzioni Helm in `base/gateway/README.md`.
- `base/catalog-service/`: ConfigMap, Secret (credenziali dev in
  chiaro, allineate a `.env`), Deployment (con readiness/liveness
  probe su `/actuator/health/{readiness,liveness}` — per questo
  abilitato `management.endpoint.health.probes.enabled=true` in
  `application.yml`, altrimenti quei path non esistono), Service e
  `HTTPRoute`.
- `overlays/{local,staging,production}`: `local` imposta `replicas: 1`;
  `staging`/`production` sono per ora placeholder identici a `base`
  (da valorizzare quando esisteranno registry immagini, secret manager,
  hostname reali).

**Gap trovato e corretto nel documento di design**: la tabella di
routing del gateway (sezione 7) instradava solo `/api/products/**`
verso `catalog-service`, ma il servizio espone anche `GET
/api/categories` — senza una rotta dedicata sarebbe stato
irraggiungibile dal gateway. Aggiunta `/api/categories/** ->
catalog-service` sia nel documento che nell'`HTTPRoute`.

**Scope volutamente limitato**: create rotte/manifest solo per
`catalog-service`, l'unico microservizio realmente implementato — niente
Deployment/Service/HTTPRoute "segnaposto" per i servizi non ancora
scritti (auth, order, payment, inventory, integration, analytics):
verranno aggiunti man mano che ciascun servizio viene implementato,
per non lasciare riferimenti a backend inesistenti.

**Decisione sull'ambiente di test**: sulla macchina è attivo un
cluster locale `kind-kind`, ma è un ambiente di esercitazione
Kubernetes personale non legato al progetto (namespace tipo
`probe-lab`, `secrets-lab`) — oltre a contesti EKS di un altro
progetto (Credem) a cui non va toccato nulla. Su richiesta
dell'utente, per ora questi manifest **non sono stati applicati a
nessun cluster**: solo scritti e validati localmente con `kubectl
kustomize` + `kubectl apply --dry-run=client` (che ha confermato la
validità di Namespace/ConfigMap/Secret/Service/Deployment; le risorse
Gateway API non sono verificabili senza le loro CRD installate da
qualche parte, il che è normale non avendo fatto alcun deploy).

### 5. Customer Web (primo frontend, Phase 1 roadmap)

Scaffolding in `frontend/customer-web/` — Angular, come da documento
di design. Scelto **Customer Web prima di Admin Web** (React) su
indicazione dell'utente.

**Decisione tecnica**: Node.js installato è v16.20.2, incompatibile
con Angular CLI 17+/Vite 5 (richiedono Node 18/20+). Su scelta
dell'utente si è usato **Angular 16** (ultimo major compatibile con
Node 16), stesso approccio già seguito per Java 11/Spring Boot 2.7 sul
Catalog Service: si lavora con l'ambiente disponibile invece di
aggiornarlo.

**Problema npm riscontrato e risolto** (stesso pattern del Maven del
Catalog Service): il registry npm di sistema punta a un registry
interno aziendale, raggiungibile solo in VPN. Anziché toccare la
configurazione npm globale della macchina, creato
un `.npmrc` **locale al progetto** (`frontend/customer-web/.npmrc`)
che punta a `registry.npmjs.org` direttamente; per il comando iniziale
`ng new` (cartella non ancora esistente) usata la variabile d'ambiente
`npm_config_registry` invece.

**Contenuto implementato**:
- `CatalogService` (client HTTP Angular) verso `/api/products`,
  `/api/products/{id}`, `/api/categories`.
- `ProductListComponent`: griglia prodotti con filtro per categoria.
- `ProductDetailComponent`: dettaglio prodotto via route `/products/:id`.
- Le chiamate usano path relativi (`/api/...`), cosi' funzionano sia in
  sviluppo (via `proxy.conf.json` verso `catalog-service` su
  `localhost:8081`) sia dietro il vero Gateway in un deploy reale,
  senza bisogno di config per-ambiente.
- `Dockerfile` multi-stage (build Node 20 in container, indipendente
  dal Node 16 dell'host, + serve statico con Nginx) e `nginx.conf` con
  fallback SPA per il routing client-side.

Verificato end-to-end: `ng build` compila senza errori; avviati
`catalog-service` (con `catalog-db` reale) e `ng serve` in parallelo,
verificato via richieste HTTP dirette che `/api/categories` e
`/api/products` passano correttamente dal dev server Angular al
backend attraverso il proxy — la pagina mostra i dati reali (incluso
il prodotto di test creato in precedenza).

**Prossimo passo naturale**: Admin Web (React) per il CRUD completo
sui prodotti, oppure proseguire con Order/Payment Service lato
backend.

### 6. README.md e piccole correzioni

Creato [README.md](../README.md) alla radice del repository con due
sezioni: come avviare Catalog Service in locale (DB, build/run Maven,
verifica via curl/Swagger, avvio facoltativo di Customer Web) e come
avviare in futuro l'infrastruttura Kubernetes con l'API Gateway
(cluster kind dedicato, installazione Envoy Gateway, build/carico
immagine, apply dell'overlay locale, port-forward per il test del
routing) — quest'ultima parte documentata ma non ancora eseguita in
questa sessione.

Su richiesta esplicita dell'utente, dal README sono stati rimossi i
riferimenti diretti al nome dell'azienda (sostituiti con "repository
interni aziendali" generico) e il link a questo diario, che non deve
essere citato pubblicamente.

Il diario stesso è stato spostato dall'utente da `CLAUDE.md` (radice)
a `.claude/CLAUDE.md`: da qui in poi **non viene più caricato
automaticamente** come contesto a inizio sessione (solo il
`CLAUDE.md` in radice lo è) — resta comunque il registro di
riferimento, va solo letto esplicitamente quando serve.

### 7. Inventory Service (secondo microservizio Python, Phase 2 roadmap)

Scaffolding completo in `services/inventory-service/` — Python +
FastAPI + SQLAlchemy, come da documento di design. Scelto tra i due
servizi Python (l'altro è Analytics Service) perché è il prossimo
passo naturale della roadmap Phase 2, insieme a Order Service (non
ancora implementato).

**Scope volutamente limitato sugli eventi**: il documento prevede che
questo servizio consumi/pubblichi eventi Kafka (`order.created`,
`inventory.reserved`, `inventory.rejected`, `inventory.released`), ma
Kafka non esiste ancora nel progetto (è nella Phase 3 della roadmap) e
nessun servizio produce `order.created` (Order Service non è stato
scritto). Per ora l'esito delle operazioni è espresso solo tramite
codici di stato HTTP (201/404/409); il producer/consumer Kafka verrà
aggiunto quando l'infrastruttura di eventi sarà pronta — annotato
esplicitamente con un commento nel codice (`app/main.py`) per non
dimenticarlo.

**Contenuto implementato**:
- Modello dati: `InventoryItem` (scorte disponibili/riservate per
  prodotto) e `Reservation` (prenotazioni), su `inventory-db`.
- API REST: `GET /api/inventory/{productId}`,
  `POST /api/inventory/reservations` (409 se le scorte disponibili non
  bastano), `DELETE /api/inventory/reservations/{id}` (rilascia la
  prenotazione e ripristina le scorte).
- Seed dati di base all'avvio (stock iniziale per i product id 1/2/3,
  idempotente).
- Test di integrazione (`tests/test_inventory_api.py`) con
  **Testcontainers** (Postgres reale, non mockato): copre lettura,
  ciclo di vita completo di una prenotazione, scorte insufficienti e
  rilascio di una prenotazione inesistente.
- `Dockerfile` (Python 3.12 slim + uvicorn).

Usato l'event handler moderno `lifespan` di FastAPI invece del
deprecato `@app.on_event("startup")`, dopo che i test iniziali
segnalavano il relativo `DeprecationWarning`.

Verificato end-to-end: `pytest` passa (5/5, Postgres reale via
Testcontainers); servizio avviato manualmente sulla porta 8083 contro
`inventory-db` reale e provato con richieste HTTP dirette (health,
lettura scorte seedate, creazione prenotazione con decremento scorte,
rilascio con ripristino scorte, rifiuto per scorte insufficienti) —
tutto funzionante.

**Prossimo passo naturale**: Order Service (Spring Boot + Kafka), che
è il pezzo mancante per chiudere il flusso Order → Inventory →
Payment descritto nella Saga Orchestration del documento di design.

### 8. Admin Web (secondo frontend, React)

Scaffolding in `frontend/admin-web/` — React + TypeScript, come da
documento di design. Dashboard per il CRUD completo sui prodotti
(create/update/delete), a differenza di Customer Web che è solo in
lettura.

**Decisione tecnica**: stesso vincolo già affrontato per Customer Web
— Node.js installato è v16.20.2, incompatibile con Vite 5 (richiede
Node 18/20+). Usato **Vite 4** (`npm create vite@4 -- --template
react-ts`), ultima major compatibile con Node 16.

**`.npmrc` locale al progetto**, stesso pattern già usato per
Customer Web e per Maven sul Catalog Service, per usare il registry
pubblico invece di quello aziendale.

**Contenuto implementato**:
- `catalogApi.ts`: client HTTP (fetch nativo, nessuna libreria
  aggiuntiva) verso `/api/products` (GET/POST/PUT/DELETE) e
  `/api/categories` (GET, sola lettura — coerente con la decisione
  presa per Catalog Service di non esporre CRUD sulle categorie).
- `ProductListPage`: tabella prodotti con azioni Modifica/Elimina.
- `ProductFormPage`: form condiviso create/edit (route
  `/products/new` e `/products/:id/edit`), con select categoria
  popolata da `/api/categories`.
- Routing via `react-router-dom`.
- Proxy di sviluppo (`vite.config.ts`) verso `catalog-service` su
  `localhost:8081`, stesso ruolo del `proxy.conf.json` di Customer
  Web.
- `Dockerfile` multi-stage (Node 20 in build + Nginx) e `nginx.conf`
  con fallback SPA, identico nell'approccio a Customer Web.

**Incidente durante il test e correzione**: avviando `docker compose`
per verificare `catalog-db` mi trovavo per errore nella cartella
`frontend/admin-web` invece che nella radice del progetto; Docker
Compose non ha trovato il `.env` lì e ha *ricreato* `catalog-db` con
credenziali vuote e una porta casuale. Rieseguito subito `docker
compose up -d catalog-db` dalla radice per ripristinare la
configurazione corretta (porta 5434, credenziali da `.env`) — il
volume dati (`catalog-db-data`) non era stato toccato, quindi nessun
dato perso, verificato poi con una query reale. **Lezione**: i comandi
`docker compose` vanno sempre lanciati dalla radice del repository,
mai passando solo `-f percorso/docker-compose.yml` da un'altra
cartella, perché il file `.env` viene cercato nella working directory
corrente, non accanto al file compose.

Trovato anche un **processo `catalog-service` rimasto orfano** da una
sessione di test precedente, ancora in ascolto sulla porta 8081:
terminato per PID esatto prima di poter riavviare il servizio pulito.

Verificato end-to-end attraverso il proxy Vite (`localhost:5173/api/...`,
non chiamando `catalog-service` direttamente): creazione prodotto
(201), lettura, aggiornamento (200, prezzo modificato) ed eliminazione
(204) — intero ciclo CRUD funzionante. Dev server e `catalog-service`
lasciati intenzionalmente attivi su richiesta dell'utente per provare
l'app dal browser.

**Prossimo passo naturale**: Order Service (Spring Boot + Kafka), per
completare Phase 2, oppure proseguire con Payment Service.

### 9. Riferimenti al nome dell'azienda rimossi anche dal diario

Su richiesta esplicita dell'utente, rimossi i riferimenti al nome
dell'azienda anche da questo diario (`.claude/CLAUDE.md`) e dal
commento nel `.mvn/settings.xml` del Catalog Service — non solo dal
README come nella richiesta iniziale — perche' il diario e' tracciato
in Git e finira' comunque su GitHub insieme al resto. Sostituiti con
formulazioni generiche ("configurazione aziendale", "repository/registry
interni aziendali").

### 10. Order Service e Payment Service (Phase 2 completata)

Scaffolding completo di entrambi in `services/order-service/` e
`services/payment-service/`, stesso schema di Catalog Service (Spring
Boot 2.7/Java 11, `.mvn/settings.xml` locale, REST + DB via
Testcontainers, Kafka rimandato alla Phase 3 come gia' fatto per
Inventory Service — annotato nel codice).

**Order Service** (`orders-db`, porta 8082):
- Modello: `Order` (stato CREATED/CONFIRMED/CANCELLED, totale calcolato
  dagli item) con `OrderItem` in cascata (`OneToMany` con
  `orphanRemoval`).
- API: `POST/GET /api/orders`, `GET /api/orders/{id}`,
  `PATCH /api/orders/{id}/status`.
- Regola di business: un ordine CANCELLED non puo' piu' cambiare stato
  (409 se ci si prova).

**Payment Service** (`payments-db`, porta 8084):
- API: `POST /api/payments`, `GET /api/payments/{id}`.
- Senza un gateway di pagamento reale ne' Kafka, l'esito
  (COMPLETED/FAILED) e' deciso da una **regola simulata e dichiarata
  come tale nel codice** (soglia arbitraria sull'importo) invece di
  essere sempre COMPLETED, cosi' resta testabile anche il percorso di
  rifiuto.

**Due bug reali trovati dai test di Order Service e corretti**:
1. Il test del 404 deserializzava la risposta di errore
   (`ApiError`, con `status` numerico) come `OrderResponse` (con
   `status` enum) — conflitto Jackson. Fix: nel test si legge il body
   come `String` quando interessa solo il codice HTTP (stesso pattern
   riapplicato preventivamente nei test di Payment Service).
2. `TestRestTemplate` con il solo `HttpURLConnection` del JDK non
   supporta il metodo PATCH. Fix: aggiunta `org.apache.httpcomponents:
   httpclient` (v4, non v5 — la v5 richiede Spring 6/Boot 3, qui siamo
   su Boot 2.7/Spring 5.3) come dipendenza di test, che Spring Boot
   rileva automaticamente per configurare un client HTTP che supporta
   PATCH.

Verificato end-to-end contro i DB reali: creazione ordine, lettura,
transizione di stato CONFIRMED, lista ordini; creazione pagamento
riuscito (COMPLETED) e uno sopra soglia (FAILED), lettura, 404 su
pagamento inesistente — tutto funzionante.

### 11. Flusso carrello -> ordine -> pagamento in Customer Web

Su richiesta dell'utente di poter provare Order Service e Payment
Service dal browser, aggiunto un flusso di checkout minimale a
Customer Web (Angular):

- `CartService`: carrello in memoria (nessuna persistenza, si perde al
  refresh — sufficiente per il test manuale, non era richiesta
  persistenza).
- Pulsante "Aggiungi al carrello" su lista prodotti e dettaglio
  prodotto; contatore carrello nell'header.
- `CheckoutComponent` (`/checkout`): riepilogo carrello -> form email
  cliente -> crea ordine (`OrderService`) -> mostra conferma e importo
  -> crea pagamento (`PaymentService`) -> mostra esito
  COMPLETED/FAILED. Il carrello viene svuotato solo se il pagamento va
  a buon fine.

**Aggiornamento necessario al proxy di sviluppo**: `proxy.conf.json`
instradava tutto `/api` verso `catalog-service` (8081). Con tre
backend distinti dietro `/api/...` servono regole per prefisso:
`/api/products` e `/api/categories` -> 8081, `/api/orders` -> 8082
(Order Service), `/api/payments` -> 8084 (Payment Service) — lo stesso
schema di routing che avra' il vero API Gateway. Nota: a differenza
delle modifiche al codice, il proxy viene letto solo all'avvio di `ng
serve`, quindi e' stato necessario riavviare il dev server perche' la
modifica avesse effetto.

Verificato end-to-end attraverso il proxy (`localhost:4200/api/...`):
categorie, creazione ordine, creazione pagamento — tutti instradati
correttamente al servizio giusto. Dev server Angular, catalog-service,
order-service e payment-service lasciati attivi per il test
dell'utente dal browser.

**Prossimo passo naturale**: Auth Service (Keycloak/OAuth2) per dare
identita' reale ai clienti invece dell'email libera, oppure Kafka
(Phase 3) per collegare davvero Order -> Inventory -> Payment tramite
eventi invece di chiamate dirette dal frontend.

### 12. Kafka (Phase 3, infrastruttura)

Su richiesta generica dell'utente ("fai la parte infrastructure"),
chiarita poi con una domanda diretta tra le quattro parti previste in
`infrastructure/` (Kafka, Keycloak, Monitoring, Gateway): scelto
**Kafka**, propedeutico a sbloccare la Saga Orchestration gia'
progettata ma finora solo annotata come TODO in Inventory/Order/Payment
Service.

**Decisione tecnica**: broker in modalita' **KRaft** (senza Zookeeper),
immagine ufficiale `apache/kafka:3.8.0` — piu' semplice da gestire in
locale di un cluster Zookeeper+Kafka separato, e ormai lo standard per
installazioni nuove. Aggiunto a `docker-compose.yml` insieme a:
- `kafka-init`: container "one-shot" che crea i 12 topic dell'event
  catalog (documento di design, sezione 8) ad ogni avvio, idempotente
  (`--if-not-exists`).
- `kafka-ui` (Provectus): interfaccia web per ispezionare topic e
  messaggi, utile data la natura dimostrativa/didattica del progetto —
  aggiunta anche se non esplicitamente richiesta perche' rende
  verificabile "e' arrivato l'evento?" senza CLI, cosa che servira'
  appena ci sara' un producer/consumer reale.

**Bug reale trovato e corretto durante il test**: l'healthcheck del
broker (`kafka-broker-api-versions.sh --bootstrap-server
localhost:9092`) falliva sempre, container bloccato in stato
`starting`. Causa: `KAFKA_LISTENERS` legava il listener PLAINTEXT
all'hostname specifico `kafka:9092` invece che a tutte le interfacce,
quindi da *dentro* il container stesso `localhost:9092` non era
raggiungibile (il bind risolveva solo l'IP del container sulla rete
Docker, non anche `127.0.0.1`). **Fix**: `KAFKA_LISTENERS` su
`0.0.0.0` per tutti i listener interni; `KAFKA_ADVERTISED_LISTENERS`
resta invece con l'hostname/porta specifici (`kafka:9092` per gli
altri container, `localhost:9094` per l'host) — e' quest'ultimo che
i client usano per sapere *come* connettersi, mentre `LISTENERS`
definisce solo su cosa il broker resta in ascolto.

**Scope volutamente limitato**: solo l'infrastruttura (broker + topic).
Nessun producer/consumer collegato nei microservizi esistenti — quel
collegamento e' la Saga Orchestration a carico dell'Integration
Service (Apache Camel, non ancora implementato), non qualcosa da
anticipare con logica ad-hoc in Order/Inventory/Payment.

Verificato end-to-end: `kafka` healthy, tutti i 12 topic creati
correttamente (log di `kafka-init`, terminato con `exited (0)`),
`kafka-ui` raggiungibile su `http://localhost:8090` (HTTP 200).
Documentato in [README.md](../README.md) (nuova sezione 2, con
rinumerazione della sezione Kubernetes da 2.x a 3.x) e in
`infrastructure/kafka/README.md`.

**Prossimo passo naturale**: collegare Order Service a pubblicare
`order.created` e avviare l'Integration Service (Apache Camel) come
orchestratore della saga, oppure Auth Service (Keycloak) per
l'identita' reale dei clienti.

### 13. Manifest Kubernetes per Order/Inventory/Payment Service

Dopo Kafka, seconda parte della richiesta generica "fai la parte
infrastructure": finora solo `catalog-service` aveva manifest completi
in `infrastructure/kubernetes/base/`, gli altri tre servizi scritti da
allora (Order, Inventory, Payment) ne erano privi. Chiesto
esplicitamente all'utente se completare i manifest o testare dal vivo
su un cluster kind dedicato (come proposto in precedenza): scelto
**solo il completamento dei manifest**, senza deploy.

Aggiunti `base/order-service/`, `base/inventory-service/`,
`base/payment-service/`, stesso schema di `catalog-service`
(ConfigMap, Secret, Deployment con probe, Service, HTTPRoute), con due
differenze degne di nota:

- **Inventory Service** (FastAPI) espone solo `/health`, non i gruppi
  `readiness`/`liveness` separati di Spring Boot Actuator: entrambi i
  probe del Deployment puntano li', annotato nel manifest stesso —
  meno preciso di un readiness check che verifichi davvero la
  connessione al DB, ma coerente con cio' che il servizio espone
  realmente oggi.
- **HTTPRoute** per servizio: `/api/orders` -> order-service,
  `/api/inventory` -> inventory-service, `/api/payments` ->
  payment-service, seguendo la tabella di routing del documento di
  design (sezione 7).

Aggiornati di conseguenza `base/kustomization.yaml` (include dei tre
nuovi componenti) e `overlays/local/kustomization.yaml`: stesse due
patch gia' usate per catalog-service (repliche a 1, DB via
`host.docker.internal:<porta>`) replicate per gli altri tre.

Verificato solo localmente, come da richiesta: `kubectl kustomize
overlays/local` renderizza correttamente tutti e 4 i servizi (429
righe, patch applicate correttamente su tutti i ConfigMap/Deployment),
`kubectl apply --dry-run=client` valida gli 8 oggetti standard
(ConfigMap/Secret/Service/Deployment x4) — Gateway/GatewayClass/HTTPRoute
restano non verificabili senza le loro CRD, come gia' per
catalog-service, essendo tuttora un deploy mai eseguito.

Aggiornato [README.md](../README.md) sezione 3: build/load immagine
ora in loop su tutti e 4 i servizi, curl di verifica estesi a
orders/inventory/payments.

**Prossimo passo naturale**: provare per davvero il deploy su un
cluster kind dedicato (proposta ancora in sospeso), oppure Integration
Service (Camel) per la saga.

## 2026-09-05

### 14. Saga Orchestration: Integration Service (Camel) e collegamento di Order/Inventory/Payment a Kafka

Ripresa del lavoro dopo la sessione del 2026-08-28. Kafka esisteva come
infrastruttura (broker + topic) ma nessun servizio ci pubblicava o
consumava nulla: la Saga Orchestration, cuore del documento di design,
era solo un TODO annotato nel codice di Order/Inventory/Payment. Scelto
dall'utente, tra quattro opzioni, di chiudere proprio questo pezzo.

**Contraddizione trovata nel documento di design e risolta**: la
sezione "Saga Orchestration" diceva che l'Integration Service *invoca
via REST* l'Inventory Service, mentre la scheda dell'Inventory Service
elencava `order.created` tra gli eventi che consuma. Scelta la versione
**interamente a eventi** (Inventory consuma `order.created`), coerente
con il principio "Event First" e con l'event catalog gia' definito, e
aggiornata di conseguenza la sezione del documento.

**Flusso implementato** (nessun topic nuovo tranne una DLQ):

| Servizio | Consuma | Pubblica |
|---|---|---|
| Order | `order.updated`, `order.cancelled` | `order.created` |
| Inventory | `order.created`, `order.cancelled` | `inventory.reserved` / `rejected` / `released` |
| Payment | `payment.requested` | `payment.completed` / `payment.failed` |
| Integration (orchestratore) | tutti gli esiti | `payment.requested`, `order.updated`, `order.cancelled` |

La compensazione non ha un evento dedicato: `order.cancelled` e' anche
il segnale che fa rilasciare le scorte all'Inventory Service.

**Integration Service** (nuovo, `services/integration-service/`, porta
8085): Spring Boot 2.7 + **Camel 3.22.2** (ultima serie compatibile con
Spring Boot 2.7/Java 11; Camel 4 richiede Boot 3). Cinque rotte tutte
della stessa forma - consuma un evento, chiedi all'orchestratore il
passo successivo, pubblicalo - con la logica di coordinamento isolata
in `SagaOrchestrator`, che non conosce Camel. Lo stato delle saghe in
corso e' in memoria (`SagaStateStore`): serve perche' `inventory.reserved`
non trasporta l'importo da pagare, che sta solo in `order.created`. Il
limite e' dichiarato nel codice e nel documento; il Deployment
Kubernetes ha percio' **una sola replica**, altrimenti i passi della
stessa saga finirebbero su pod che non condividono quello stato.

**Dettagli non ovvi degli altri tre servizi**:
- *Order Service*: l'evento `order.created` viene pubblicato **dopo il
  commit** (`@TransactionalEventListener(AFTER_COMMIT)`), non dentro la
  transazione: altrimenti si annuncerebbe un ordine che un rollback
  puo' far sparire, o che i consumer vedrebbero prima che sia leggibile
  dal database. Annotato che la soluzione completa sarebbe il pattern
  Transactional Outbox.
- *Payment Service*: consumo idempotente sull'ordine - se un pagamento
  per quell'ordine esiste gia', non riaddebita ma **ripubblica l'esito**
  (l'orchestratore potrebbe non aver visto il primo).
- *Inventory Service*: aggiunta la colonna `reservations.order_id`, che
  serve alla compensazione (rilasciare tutte le prenotazioni di un
  ordine) e alla verifica di idempotenza. `create_all` non altera
  tabelle esistenti, quindi c'e' un `ALTER TABLE ... ADD COLUMN IF NOT
  EXISTS` all'avvio, dichiarato come sostituto di uno strumento di
  migrazione (Alembic) non ancora presente. Il consumer usa
  **confluent-kafka** su un thread dedicato, non asyncio, perche'
  l'accesso al DB (SQLAlchemy) e' sincrono e bloccherebbe il loop di
  FastAPI. Con `EVENTS_ENABLED=false` il servizio resta sola API REST.

**Bug reale trovato e corretto (test dell'Integration Service tutti
falliti, 500 secondi)**: l'error handler Camel era
`deadLetterChannel("kafka:saga.dlq")`. Camel avvolge con l'error
handler **ogni singolo processore di ogni rotta**, quindi all'avvio
nascevano una quarantina di producer Kafka verso la DLQ (46 nei log),
tutti fermi in `INIT_PRODUCER_ID`: il broker non rispondeva piu' e i
consumer non riuscivano nemmeno a entrare nel consumer group. **Fix**:
la DLQ punta a un endpoint `direct:saga-dlq`, e una singola rotta
inoltra da li' a Kafka - un solo producer. Test da 5 falliti in 500s a
5 verdi in 51s, producer da 46 a 14.

**Frontend (Customer Web)**: il checkout creava l'ordine e poi chiamava
lui stesso `POST /api/payments`; con la saga attiva sarebbe nato un
secondo pagamento. Ora crea solo l'ordine e ne attende l'esito
rileggendolo finche' non esce da `CREATED` (`awaitSagaOutcome`),
mostrando "confermato" o "annullato". Rimossi `payment.service.ts` e
`payment.model.ts`, non piu' usati da nessuno.

**Test aggiunti**, tutti contro Kafka reale (Testcontainers), nessun
mock: Order 6/6, Payment 6/6, Integration 5/5, Inventory 9/9. Nei test
Python i container sono passati a `conftest.py` e a scope di sessione:
`app.database` costruisce l'engine all'import, quindi due file di test
con container propri avrebbero puntato entrambi al database del primo.
Corretto anche un test che sembrava verificare la ri-consegna ma
poteva passare per caso (`await_event` rilegge il topic dall'inizio e
restituiva lo stesso evento di prima): ora si attende un numero
preciso di eventi.

**Verifica end-to-end** con i quattro servizi avviati contro i DB e il
Kafka reali - i contatori per topic combaciano esattamente con i tre
scenari provati:

| scenario | esito ordine | scorte |
|---|---|---|
| acquisto normale | CONFIRMED | 2 pezzi riservati |
| importo oltre soglia (pagamento rifiutato) | CANCELLED | riservate e poi **rilasciate** |
| quantita' superiore alle scorte | CANCELLED | invariate, nessun pagamento creato |

`order.created` 3, `inventory.reserved` 2, `inventory.rejected` 1,
`inventory.released` 1, `payment.requested` 2, `payment.completed` 1,
`payment.failed` 1, `order.updated` 1, `order.cancelled` 2,
**`saga.dlq` 0**.

**Kubernetes**: aggiunti i manifest di `integration-service`
(ConfigMap, Deployment a una replica, Service; **nessun HTTPRoute**, il
servizio non espone API pubbliche) e `KAFKA_BOOTSTRAP_SERVERS` nei
ConfigMap degli altri tre, con l'overlay `local` che lo fa puntare a
`host.docker.internal:9094` - stesso schema gia' usato per i database.
Come sempre finora, solo `kubectl kustomize` + `--dry-run=client`,
nessun deploy.

*Piccolo intoppo*: un em dash in un commento di `service.yaml` mandava
in errore `kubectl kustomize` ("invalid trailing UTF-8 octet"); i
manifest restano quindi in puro ASCII come gia' erano.

Aggiornati [README.md](../README.md) (nuova sezione 3 con i tre
scenari da provare, rinumerata la sezione Kubernetes da 3 a 4),
`infrastructure/kafka/README.md` (tabella producer/consumer per topic)
e il documento di design.

**Prossimo passo naturale**: Keycloak/OAuth2 (Phase 4), per dare
identita' reale ai clienti al posto dell'email libera nel checkout,
oppure il deploy vero su un cluster kind dedicato - proposta ancora in
sospeso da tre sessioni.
