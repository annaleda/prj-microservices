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

### 15. Keycloak e OAuth2: le API non sono piu' aperte (Phase 4)

Chiusa la Phase 3, scelta la Phase 4 della roadmap. Fino a qui chiunque
poteva creare ordini intestandoli a un'email scritta a mano e l'Admin
Web faceva CRUD sul catalogo senza autenticarsi.

**Keycloak** aggiunto a `docker-compose.yml` (immagine 25.0, `start-dev`),
e finalmente **`auth-db` viene usato**: era stato creato nella prima
sessione per l'area autenticazione ed era rimasto vuoto da allora. Il
realm non si configura dalla console ma si **importa da JSON**
(`infrastructure/keycloak/realm-polyglot-commerce.json`, montato e
caricato con `--import-realm`): ruoli, client e utenti demo sono
versionati e ricreabili da zero. Ruoli come da documento di design:
CUSTOMER, ADMIN, WAREHOUSE, SUPPORT. Due client pubblici con PKCE
(`customer-web`, `admin-web`): girano nel browser e non possono
custodire un segreto.

**Resource server** in Catalog, Order, Payment (Spring Security OAuth2)
e Inventory (PyJWT + JWKS scritto a mano). L'Integration Service non ha
API HTTP - parla solo per eventi - quindi non c'e' nulla da proteggere.
Nessun segreto e' condiviso con Keycloak: i servizi verificano la firma
con le chiavi pubbliche del realm.

Chi puo' fare cosa e' riassunto nel README (sezione 3.1) e nel documento
di design (sezione 9). Le due decisioni non banali:
- il **catalogo resta pubblico in lettura**: e' la vetrina, senza
  sarebbe un negozio a porte chiuse;
- un **ordine e' intestato a chi presenta il token**: `customerEmail` e'
  sparito da `OrderRequest`, e un cliente che chiede l'ordine di un
  altro riceve **403 e non 404** (l'ordine esiste, non e' suo). Il
  filtro "vedo solo i miei ordini" sta nel service e non nella
  configurazione di sicurezza, perche' e' una regola sui dati, non
  sull'URL.

**Trappola trovata scrivendo i test (una mezz'ora persa)**: i token
finti dei test avevano forma `utente:RUOLI:email` e venivano rifiutati
con 401 senza mai arrivare al decoder. Il motivo e' che il filtro dei
bearer token accetta solo i caratteri previsti da RFC 6750
(`token68`): ':' e '@' lo rendono malformato. Il payload finto ora
viaggia in **base64url**.

**Come sono testate le autorizzazioni**: con un `JwtDecoder` finto nei
servizi Java e la sostituzione della dipendenza che valida il token in
quello Python. Le regole verificate sono quelle vere (chi ottiene 200,
chi 401, chi 403); a non essere verificata nei test e' la firma, che
Keycloak sa gia' fare. Avviare un identity provider per ogni classe di
test costerebbe minuti e legherebbe i test alla sua configurazione.
Dichiarare un bean `JwtDecoder` ha anche l'effetto utile di evitare che
Spring contatti l'issuer all'avvio dei test.

Test: Catalog 5/5, Order 11/11, Payment 9/9, Inventory 12/12,
Integration 5/5 (invariato).

**Frontend**. Customer Web: `angular-oauth2-oidc`, con
`OAuthModule.forRoot({resourceServer: ...})` che allega da solo il token
alle chiamate `/api` - nessun interceptor scritto a mano. Il catalogo
resta sfogliabile da disconnessi e il login serve al checkout, dove il
campo email e' stato tolto. Admin Web: `react-oidc-context`, con un
ponte esplicito (`api/authToken.ts`) fra il contesto React e il client
`fetch`, che essendo fatto di funzioni non puo' usare hook; chi entra
senza ruolo ADMIN vede un messaggio chiaro invece di una console che
risponde 403 a ogni click.

**Verifica end-to-end con token veri firmati da Keycloak** (non i finti
dei test):

| Richiesta | Senza token | CUSTOMER | ADMIN / WAREHOUSE |
|---|---|---|---|
| GET /api/products | 200 | 200 | 200 |
| POST /api/products | 401 | 403 | 201 (ADMIN) |
| GET /api/inventory/1 | 401 | 200 | 200 |
| POST prenotazione | 401 | 403 | 201 (WAREHOUSE) |
| POST /api/orders | 401 | 201 | 403 (ADMIN non e' un cliente) |
| PATCH stato ordine | 401 | 403 | 200 (ADMIN) |
| GET /api/payments/1 | 401 | 403 | 200 (ADMIN) |

L'ordine creato con il token di `customer` risulta intestato a
`customer@example.com` senza che l'email sia mai stata inviata, e la
saga lo ha portato a CONFIRMED come prima: la sicurezza HTTP non
interferisce con il flusso a eventi. Nella lista ordini il cliente ne
vede 1, l'admin tutti e 9.

**Cosa non e' stato verificato**: il redirect di login vero e proprio,
che richiede un browser. Sono stati controllati i pezzi che di solito
si sbagliano - l'endpoint di autorizzazione accetta i due client con i
loro redirect_uri, rifiuta un redirect_uri estraneo, e il documento di
discovery risponde con CORS all'origine del frontend.

**Kubernetes**: aggiunto `KEYCLOAK_ISSUER_URI` ai ConfigMap dei quattro
servizi. Nell'overlay `local` c'e' un avvertimento che vale la pena
ricordare: quell'URL non serve solo a *raggiungere* Keycloak, decide
anche quale `iss` e' accettabile. Se il browser prende i token da
`localhost:8180` e il servizio si aspetta un altro hostname, li rifiuta
tutti pur essendo la firma valida; perche' funzioni, Keycloak deve
essere raggiungibile allo stesso URL da browser e da pod (KC_HOSTNAME).
E' il tipo di dettaglio che si scopre solo al primo deploy reale.

**Volutamente non fatto**: il client per l'OAuth2 Client Credentials
Grant previsto dal documento. Oggi nessun servizio chiama un altro via
HTTP - comunicano solo per eventi - quindi sarebbero credenziali senza
alcun uso. Annotato in `infrastructure/keycloak/README.md`. Allo stesso
modo l'**Auth Service** del documento resta non implementato:
identita' e ruoli li gestisce Keycloak, e quel servizio avra' senso
quando ci saranno dati di profilo applicativi (indirizzi, preferenze,
consensi). Segnalato nel documento di design.

**Prossimo passo naturale**: Notification Service (chiude gli ultimi
topic dell'event catalog senza consumer), Observability (Phase 5, ora
che c'e' un correlationId da propagare in un flusso a quattro
servizi), oppure il deploy vero su cluster kind - in sospeso da tre
sessioni, e ora con in piu' la questione dell'issuer da risolvere.

### 16. Il carrello non sopravviveva al login (bug introdotto dalla 15)

Domanda dell'utente: "se non ho acceduto, mi permette di aggiungere al
carrello lo stesso?". Si', e il comportamento in se' e' quello giusto -
il carrello anonimo e' lo standard nei negozi, si sfoglia, si aggiunge e
il login si chiede alla cassa. Il problema era cosa succedeva dopo.

Il carrello viveva **solo in memoria** (scelta annotata nella sezione
11: "si perde al refresh, sufficiente per il test manuale"). Con il
login OIDC quella scelta e' diventata un difetto: `initCodeFlow()` e' un
redirect vero, la pagina lascia l'applicazione e torna ricaricata.
Percorso reale: aggiungi al carrello -> cassa -> "devi accedere" ->
accedi -> **torni con il carrello vuoto**. Il login distruggeva
esattamente cio' che doveva sbloccare.

**Fix**: `CartService` persiste in `localStorage` (scritture e letture
protette da try/catch: in finestra anonima o con storage negato il
carrello continua a funzionare in memoria invece di far esplodere
l'app). Aggiunto anche lo svuotamento del carrello al logout, altrimenti
chi usa lo stesso computer dopo si troverebbe la spesa di qualcun altro.

**Primi test frontend del progetto**, perche' "compila" non dimostrava
niente su un bug di persistenza: 4 test su `CartService` in Chrome
headless (`ng test --watch=false --browsers=ChromeHeadless`), fra cui
quello che simula il redirect di login costruendo una seconda istanza
del servizio - e' esattamente cio' che accade al ritorno da Keycloak.
Sistemati anche i 3 test generati dal CLI su `AppComponent`, che non
reggevano piu' da quando il componente inietta `AuthService`: ora ne
ricevono uno finto. Totale 7/7.

Lezione: una scelta puo' essere corretta quando viene presa e diventare
un bug per via di qualcosa aggiunto dopo, senza che nessuno tocchi il
codice in questione.

### 17. Icona carrello e storico ordini in Customer Web

Su richiesta dell'utente: la scritta "Carrello (N)" nell'header e'
diventata un'**icona** con il contatore come badge, e accanto e' stata
aggiunta una pagina **"I miei ordini"**.

Dettagli di resa:
- icone SVG scritte a mano invece di una libreria di icone: due sole
  icone non giustificano una dipendenza in piu';
- il badge del contatore non viene disegnato con zero articoli
  (l'espressione e' falsy e `*ngIf` lo salta): uno "0" perenne accanto
  al carrello e' rumore;
- togliendo il testo servono le etichette per chi non vede l'icona:
  `title` e `aria-label` su entrambi i link, con il numero di articoli
  dentro l'etichetta del carrello;
- lo storico compare solo da collegati - da disconnessi darebbe 401 -
  ma la pagina raggiunta direttamente via URL non esplode: mostra
  l'invito ad accedere, e distingue la sessione scaduta (401) da un
  errore del servizio.

**Corretto anche il backend**: `GET /api/orders` restituiva gli ordini
senza ordinamento esplicito, quindi il database era libero di darli
come capitava - uno "storico" cosi' non e' uno storico. Ora arrivano
dal piu' recente (`findByCustomerEmailOrderByCreatedAtDesc` per i
clienti, `Sort` su `createdAt` per il personale interno), con un test
che lo verifica: 12/12 in Order Service, 7/7 nei test frontend.

Verificato dal vivo attraverso il proxy: quattro ordini dello stesso
cliente, dal piu' recente al piu' vecchio, con stato e totale corretti.

### 18. Nessun modo evidente di tornare al catalogo

Segnalazione dell'utente: dallo storico non si torna al catalogo. Vero
solo in parte - il nome del negozio nell'header e' un link alla home -
ma il punto era giusto: non e' un appiglio evidente, e con la lista
degli ordini piena non c'era nient'altro (il link "Vai al catalogo"
compariva solo nello stato vuoto). Stesso problema sul checkout con il
carrello pieno.

Aggiunta quindi una **terza icona per il catalogo** nell'header
(griglia di prodotti), accanto a storico e carrello, invece di rattoppare
la singola pagina: cosi' il modo per tornare indietro e' lo stesso
ovunque. Tutte e tre hanno `routerLinkActive`, per far vedere in che
sezione ci si trova. La prima versione era un link testuale "Catalogo";
sostituito con l'icona su richiesta dell'utente, per coerenza con le
altre due.

**Verifica con screenshot** (Chrome headless su `localhost:4200`, non
solo "compila"): sul catalogo l'icona a griglia risulta attiva e quella
del carrello no, sul checkout si invertono - e le icone si vedono e
sono allineate, cosa che nessun test automatico del progetto avrebbe
colto.

### 19. Grafica dell'applicazione, tema di Keycloak e registrazione

Richiesta dell'utente: una grafica piu' curata, una grafica per il login
"sopra a Keycloak" e una pagina di registrazione con lo stesso aspetto.

**Applicazione**: introdotto un piccolo sistema di stili in
`styles.scss` (colori, spaziature, raggi, ombre, tipografia Inter) su
cui i componenti si appoggiano, invece di valori ripetuti file per file.
Header con monogramma, schede prodotto con una banda colorata derivata
dal nome (il catalogo non ha immagini: meglio una tinta stabile e
riconoscibile che un finto segnaposto), stati della saga come riquadri
colorati, tabella dello storico ripulita.

**Decisione tecnica importante sul login.** "Sopra a Keycloak" si poteva
leggere in due modi: un modulo di login dentro l'applicazione Angular
che manda username e password a Keycloak (Direct Access Grant), oppure
un **tema di Keycloak** con la nostra grafica. Scelto il tema, perche'
il primo modo e' un anti-pattern: l'applicazione vedrebbe le password
in chiaro, si perderebbero SSO, MFA, recupero password e protezione
dai tentativi ripetuti, e il grant in questione e' sconsigliato in
OAuth 2.1. Con il tema si ottiene la stessa cosa che l'utente voleva -
pagine di accesso e registrazione con la grafica del negozio - senza
che le credenziali passino mai dall'applicazione.

Il tema (`infrastructure/keycloak/themes/polyglot/`) **eredita da
`keycloak` e sovrascrive solo il CSS**: i template FreeMarker restano
gli originali, cosi' un aggiornamento di Keycloak non obbliga a
riallineare pagine che non abbiamo scritto. Attenzione a una
particolarita': `styles` nel `theme.properties` *sostituisce* il valore
del genitore invece di aggiungersi, quindi `css/login.css` va ripetuto.

**Registrazione**: abilitata nel realm (`registrationAllowed`), quindi
e' Keycloak stesso a fornire la pagina - gia' con validazione, password
di conferma, controllo dei duplicati - nel nostro tema. Dall'header
dell'app il pulsante "Registrati" porta all'endpoint
`/protocol/openid-connect/registrations`, che e' lo stesso flusso del
login (authorization code + PKCE) su un percorso diverso: chi si
registra torna nell'app gia' collegato.

**Dettaglio che avrebbe reso inutile la registrazione**: un nuovo
iscritto non aveva alcun ruolo, quindi avrebbe preso 403 al primo
ordine. Aggiunto CUSTOMER ai ruoli di default del realm.

Aggiunta anche la lingua italiana (`defaultLocale`), con il solo
italiano fra le lingue supportate: dichiarando anche l'inglese vinceva
quello, perche' Keycloak segue la lingua del browser.

**Note di percorso**: la rimozione del volume di `auth-db` per
rileggere il realm da zero e' stata bloccata (azione distruttiva), e
l'import ignora i realm gia' esistenti; il realm in esecuzione e' stato
quindi allineato via API di amministrazione, restando il JSON la fonte
di verita' per gli ambienti creati da zero. Docker Desktop e' inoltre
andato in timeout al primo tentativo di condividere la cartella del
tema, riuscito al secondo.

**Verifica**: quattro giri di screenshot con Chrome headless, non
"compila". Hanno fatto emergere cose che nessun test del progetto
avrebbe visto: la pagina in inglese, una fascia grigia ereditata dal
tema PatternFly (`#kc-info-wrapper`, un contenitore intermedio che i
primi selettori saltavano), due righe di separazione invece di una, e
il titolo della registrazione spostato perche' sta in una colonna
Bootstrap larga 10/12.

### 20. Ordini di qualcun altro nello storico (vulnerabilita' reale)

Segnalazione dell'utente: "mi sono registrata e ho due ordini che non ho
mai fatto".

Verificato subito sui dati: l'utente si era registrata con la propria
email, e nel database c'erano **esattamente due ordini con quello stesso
indirizzo**, creati il 28 agosto e il 5 settembre - cioe' quando il
checkout aveva ancora il campo email libero, prima dell'autenticazione.

La causa non era il dato vecchio ma il criterio: **la proprieta' di un
ordine era decisa dall'email**. L'email non e' una prova di identita' -
si puo' cambiare, e in fase di registrazione chiunque puo' dichiarare
quella di un altro, tanto piu' qui dove Keycloak non la verifica.
Bastava quindi registrarsi con l'indirizzo di una persona per vederne
gli ordini.

**Correzione**: la proprieta' si basa sul claim `sub` del token,
l'identificativo stabile assegnato dall'identity provider. Aggiunta la
colonna `orders.customer_id`; `customerEmail` resta, ma solo per
mostrarla e per le future notifiche, non per autorizzare. Gli ordini
creati prima dell'autenticazione hanno `customer_id` nullo: non sono
attribuibili a nessun account e restano visibili al solo personale
interno.

Test di regressione che inchioda il comportamento: due account con la
**stessa email** e identita' diverse non si vedono gli ordini a vicenda
(lista filtrata e 403 sull'accesso diretto). Order Service 13/13.

Verificato dal vivo: i 14 ordini preesistenti sono scomparsi dallo
storico dei clienti (`customer` passa da 6 a 0) restando visibili
all'admin (14), e un ordine nuovo creato con il token viene legato
all'identita' e ricompare correttamente.

Lezione, gemella di quella della sezione 16: una scelta ragionevole
quando e' stata presa - intestare l'ordine all'email digitata - diventa
una falla nel momento in cui accanto nasce un sistema di identita'.

### 21. Foto dei prodotti

Richiesta dell'utente: immagini realistiche al posto della banda
colorata. La banda non era una scelta estetica ma una conseguenza: il
modello `Product` non aveva un campo immagine, quindi non c'era nulla da
mostrare.

Aggiunto `products.image_url` (catalog-service), passato per DTO,
servizio e form di Admin Web ("URL immagine"). **Si memorizza solo
l'indirizzo, non il file**: gestire i binari (upload, ridimensionamento,
CDN) e' un problema a se', da affrontare quando servira' davvero.

Nel frontend l'immagine ha un **doppio ripiego**: se il prodotto non ha
un indirizzo, oppure se l'immagine non si carica (`(error)` sul tag
img), ricompare la banda colorata di prima. Un URL esterno puo' sempre
rompersi, e un riquadro rotto e' peggio di un segnaposto.

Aggiunti 9 prodotti dimostrativi in `data.sql` (inserimenti idempotenti
per SKU, come gia' le categorie), distribuiti sulle tre categorie: prima
il catalogo aveva due sole voci, di cui una era un prodotto di test
creato da me per verificare il ruolo ADMIN — eliminato.

**Le foto vengono da loremflickr**, un servizio pubblico che restituisce
fotografie reali per parola chiave. Ha un limite di cui vale la pena
avere memoria: la corrispondenza e' approssimativa. "keyboard" pescava
pianoforti, "mouse,computer" ancora pianoforti, "backpack,bag" scene di
strada. Le foto sono state **scaricate e guardate** una per una fino a
trovare tag affidabili: i tag singoli e specifici (`mechanicalkeyboard`,
`computermouse`, `backpack`) funzionano meglio delle combinazioni.
Restano imperfezioni — il servizio sceglie a caso dentro il gruppo di
foto con quel tag, quindi l'immagine cambia a ogni caricamento. In un
negozio vero si mettono le proprie foto, ed e' esattamente cio' che il
campo consente.

Verifica: catalog-service 5/5 (test esteso all'immagine), build di
entrambi i frontend, e screenshot del catalogo per controllare che le
foto si vedano davvero e riempiano la scheda senza deformarsi
(`object-fit: cover`).

### In sospeso: Admin Web

Annotazione dell'utente (5 settembre 2026): **la parte admin e' messa
male**, da rivedere in una sessione successiva.

Contesto per quando si riprendera': Admin Web (`frontend/admin-web/`,
React + Vite) e' l'unica parte rimasta indietro rispetto al resto. Ha
ricevuto solo il minimo indispensabile - login OIDC e blocco per chi non
ha il ruolo ADMIN - mentre il sistema di stili, la tipografia Inter, le
schede e i riquadri di stato introdotti in Customer Web (sezione 19) non
sono mai stati portati qui: usa ancora il CSS generato dal template di
Vite. Da chiarire con l'utente se il problema sia l'aspetto, la
struttura delle pagine (oggi solo elenco prodotti e form) o entrambi.
