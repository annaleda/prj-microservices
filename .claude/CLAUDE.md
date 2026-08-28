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
