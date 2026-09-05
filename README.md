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
[sezione 4](#4-provare-la-saga-order---inventory---payment).

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
> nella [sezione 4](#4-provare-la-saga-order---inventory---payment).
> Dettagli in [infrastructure/kafka/README.md](infrastructure/kafka/README.md).

## 3. Autenticazione con Keycloak

Le API non sono piu' aperte: **Keycloak** emette i token, i servizi ne
verificano la firma con le chiavi pubbliche del realm e decidono in base
ai ruoli. Il catalogo resta l'unica cosa leggibile senza login - e' la
vetrina del negozio.

```bash
docker compose up -d keycloak
```

Console su `http://localhost:8180` (`admin`/`admin`), realm
`polyglot-commerce`. Configurazione, utenti demo e dettagli in
[infrastructure/keycloak/README.md](infrastructure/keycloak/README.md).

### 3.1 Chi puo' fare cosa

| Operazione | Chi |
|---|---|
| Sfogliare catalogo e categorie | chiunque, anche senza login |
| Creare e rileggere i propri ordini | `CUSTOMER` |
| Modificare il catalogo | `ADMIN` |
| Cambiare a mano lo stato di un ordine | `ADMIN` |
| Vedere gli ordini di tutti | `ADMIN`, `SUPPORT` |
| Creare pagamenti a mano | `ADMIN` |
| Leggere i pagamenti | `ADMIN`, `SUPPORT` |
| Leggere le scorte | qualunque utente autenticato |
| Muovere scorte e prenotazioni | `WAREHOUSE`, `ADMIN` |

Un ordine viene intestato a **chi presenta il token**: l'email non si
manda piu' nella richiesta, e un cliente non puo' leggere gli ordini di
un altro (403).

### 3.2 Ottenere un token per provare le API

Gli utenti demo hanno password uguale allo username:

```bash
TOKEN=$(curl -s -X POST   http://localhost:8180/realms/polyglot-commerce/protocol/openid-connect/token   -d client_id=customer-web -d grant_type=password   -d username=customer -d password=customer   | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

curl -s http://localhost:8083/api/inventory/1                          # 401
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8083/api/inventory/1   # 200
```

Gli altri utenti sono `admin` (ruolo ADMIN) e `warehouse` (ruolo
WAREHOUSE); per loro usa `client_id=admin-web`.

### 3.3 Dal browser

Customer Web e Admin Web fanno login con Keycloak (authorization code +
PKCE) e allegano da soli il token alle chiamate API. In Customer Web il
catalogo si sfoglia da disconnessi e il login serve solo al checkout;
Admin Web richiede il ruolo ADMIN per intero e lo dice esplicitamente a
chi entra senza averlo.

## 4. Provare la saga Order -> Inventory -> Payment

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

### 4.1 Avviare infrastruttura e servizi

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

Gli esempi che seguono usano i token della sezione 3.2:

```bash
get_token() {
  curl -s -X POST http://localhost:8180/realms/polyglot-commerce/protocol/openid-connect/token     -d "client_id=$2" -d grant_type=password -d "username=$1" -d "password=$1"     | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
}
CUSTOMER=$(get_token customer customer-web)
AUTH="Authorization: Bearer $CUSTOMER"
```

> L'Inventory Service richiede le dipendenze Python installate
> (`pip install -r requirements.txt`, preferibilmente in un
> virtualenv). Il suo consumer Kafka si puo' spegnere con
> `EVENTS_ENABLED=false` per usarlo come sola API REST.

### 4.2 Percorso felice: ordine confermato

```bash
curl -s -H "$AUTH" http://localhost:8083/api/inventory/1     # scorte prima

# Nessuna email nella richiesta: l'ordine e' intestato a chi ha il token.
curl -s -X POST http://localhost:8082/api/orders -H "$AUTH"   -H "Content-Type: application/json"   -d '{"items":[{"productId":1,"productName":"Wireless Mouse","quantity":2,"unitPrice":29.90}]}'

# dopo un paio di secondi (l'id e' quello restituito sopra)
curl -s -H "$AUTH" http://localhost:8082/api/orders/1        # status: CONFIRMED
curl -s -H "$AUTH" http://localhost:8083/api/inventory/1     # 2 pezzi riservati
```

### 4.3 Pagamento rifiutato: compensazione

Il gateway di pagamento simulato rifiuta gli importi da 10.000 in su.
L'ordine viene annullato **e le scorte riservate tornano disponibili**:

```bash
curl -s -X POST http://localhost:8082/api/orders -H "$AUTH"   -H "Content-Type: application/json"   -d '{"items":[{"productId":2,"productName":"Mechanical Keyboard","quantity":40,"unitPrice":250.00}]}'

# status: CANCELLED, e le scorte del prodotto 2 sono di nuovo quelle di partenza
curl -s -H "$AUTH" http://localhost:8083/api/inventory/2
```

### 4.4 Scorte insufficienti: saga interrotta subito

```bash
curl -s -X POST http://localhost:8082/api/orders -H "$AUTH"   -H "Content-Type: application/json"   -d '{"items":[{"productId":3,"productName":"Rare Book","quantity":9999,"unitPrice":10.00}]}'
```

L'ordine risulta `CANCELLED` senza che venga creato alcun pagamento:
la saga si ferma al rifiuto delle scorte.

### 4.5 Vedere gli eventi

Su `http://localhost:8090` (kafka-ui) si possono ispezionare i topic
uno per uno e leggere gli envelope JSON. Tutti gli eventi di una
stessa saga condividono il `correlationId`, quindi il flusso e'
ricostruibile da un capo all'altro. Il topic `saga.dlq` deve restare
vuoto: se contiene qualcosa, un consumer non e' riuscito a processare
un messaggio nemmeno dopo i tentativi previsti.

### 4.6 Dal browser

Con Customer Web (`npm start` in `frontend/customer-web`, vedi 1.4): si
accede con l'utente `customer`, si aggiunge qualcosa al carrello e il
checkout crea l'ordine e ne attende l'esito, mostrando "ordine
confermato" o "ordine annullato" quando la saga si chiude. Servono anche
Catalog Service (8081) per il catalogo e Keycloak per il login.

## 5. Avviare l'infrastruttura Kubernetes con API Gateway

I manifest si trovano in `infrastructure/kubernetes/` (pattern
base/overlays con Kustomize) e usano la **Kubernetes Gateway API**
(non un Ingress classico), implementata con **Envoy Gateway**. Finora
sono stati scritti e validati solo localmente (`kubectl kustomize` +
dry-run), **non ancora testati su un cluster reale** in questa
sessione: i passi seguenti sono la procedura prevista, da verificare
alla prima esecuzione.

### Prerequisiti

- `kubectl`, `kind`, `helm` (già presenti su questa macchina)

### 5.1 Creare un cluster dedicato

Usa un cluster **dedicato al progetto**, non un cluster kind già in
uso per altro:

```bash
kind create cluster --name polyglot-commerce
kubectl config use-context kind-polyglot-commerce
```

### 5.2 Installare Envoy Gateway (il controller della Gateway API)

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.1.0 \
  -n envoy-gateway-system \
  --create-namespace

kubectl wait --timeout=5m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

### 5.3 Costruire e caricare le immagini dei servizi

Il cluster kind non ha accesso automatico alle immagini Docker locali:
vanno costruite e caricate esplicitamente, una per servizio.

```bash
for svc in catalog-service order-service inventory-service payment-service integration-service; do
  docker build -t "polyglot-commerce/$svc:0.1.0" "services/$svc"
  kind load docker-image "polyglot-commerce/$svc:0.1.0" --name polyglot-commerce
done
```

### 5.4 Applicare i manifest (overlay locale)

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

### 5.5 Esporre il Gateway e testare il routing

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

> Solo `/api/products` e `/api/categories` rispondono 200 senza token:
> sulle altre rotte un **401 e' gia' un esito corretto**, vuol dire che
> la richiesta ha raggiunto il servizio ed e' stata respinta dal
> controllo di sicurezza, non persa dal gateway. Per ottenere 200 serve
> un token valido, e li' entra in gioco l'avvertenza sull'issuer scritta
> nell'overlay `local`.

### 5.6 Pulizia

```bash
kind delete cluster --name polyglot-commerce
```

---

## Struttura del repository

Vedi la sezione "Repository Structure" in
[polyglot-commerce-platform.md](polyglot-commerce-platform.md) per la
struttura completa prevista a fine progetto.
