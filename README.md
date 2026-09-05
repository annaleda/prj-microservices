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
docker compose up -d catalog-db kafka kafka-init minio minio-init
docker compose ps
```

`catalog-db` e `kafka` devono risultare `healthy`. Le credenziali e le
porte sono in [.env](.env) (`localhost:5434`, db `catalog`,
utente/password `catalog`/`catalog`).

Kafka serve anche solo per il catalogo: alla creazione di un prodotto
il servizio pubblica `product.created`. Senza broker raggiungibile la
lettura del catalogo funziona lo stesso, ma la creazione di un
prodotto resta in attesa finche' il producer non rinuncia.

MinIO serve invece per le immagini caricate da Admin Web (vedi
[infrastructure/minio/README.md](infrastructure/minio/README.md)); i
prodotti con un'immagine a indirizzo esterno funzionano anche senza.

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
un altro (403). L'identita' e' il claim `sub`, non l'email: quest'ultima
si puo' cambiare e chiunque puo' dichiarare quella di un altro al
momento della registrazione.

Chi non ha un account puo' **registrarsi** dal pulsante nell'header:
la pagina e' quella di Keycloak, con la grafica del negozio, e i nuovi
iscritti ricevono il ruolo `CUSTOMER`.

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
PKCE) e allegano da soli il token alle chiamate API. Le pagine di
accesso e registrazione sono servite da Keycloak con un tema dedicato
(`infrastructure/keycloak/themes/polyglot/`) che ne ricalca la grafica:
le password non passano mai dall'applicazione. In Customer Web il
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

Poi, in cinque terminali distinti:

```bash
cd services/catalog-service     && mvn -s .mvn/settings.xml spring-boot:run   # 8081
cd services/order-service       && mvn -s .mvn/settings.xml spring-boot:run   # 8082
cd services/inventory-service   && uvicorn app.main:app --port 8083           # 8083
cd services/payment-service     && mvn -s .mvn/settings.xml spring-boot:run   # 8084
cd services/integration-service && mvn -s .mvn/settings.xml spring-boot:run   # 8085
```

> L'Inventory Service richiede le dipendenze Python installate
> (`pip install -r requirements.txt`, preferibilmente in un
> virtualenv). Il suo consumer Kafka si puo' spegnere con
> `EVENTS_ENABLED=false` per usarlo come sola API REST.

Gli esempi che seguono usano i token della sezione 3.2:

```bash
get_token() {
  curl -s -X POST http://localhost:8180/realms/polyglot-commerce/protocol/openid-connect/token     -d "client_id=$2" -d grant_type=password -d "username=$1" -d "password=$1"     | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
}
CUSTOMER=$(get_token customer customer-web)
AUTH="Authorization: Bearer $CUSTOMER"
```

### 4.2 Rifornire il magazzino

**Passo necessario, non facoltativo.** Un prodotto senza scorte fa
rifiutare la riserva e la saga annulla l'ordine: senza questo passo
ogni acquisto finisce `CANCELLED`.

```bash
sh infrastructure/demo/seed-stock.sh 50    # 50 pezzi per ogni prodotto a catalogo
```

Lo script legge gli identificativi da `GET /api/products` e dichiara
le scorte con `PUT /api/inventory/{id}`, usando il token dell'utente
`warehouse`. Legge il catalogo invece di conoscerlo: una lista di id
scritta a mano si disallinea appena il catalogo cambia.

Un prodotto creato dopo ottiene subito la sua riga di magazzino — il
Catalog Service pubblica `product.created` e l'Inventory Service la
crea — ma **a zero pezzi**: le unita' vanno dichiarate.

Da **Admin Web** il form del prodotto ha il campo "Scorte disponibili",
e l'elenco marca in rosso i prodotti a zero (`non ordinabile`). Via API,
con lo script qui sopra oppure a mano:

```bash
WAREHOUSE=$(get_token warehouse admin-web)
curl -s -X PUT http://localhost:8083/api/inventory/13   -H "Authorization: Bearer $WAREHOUSE" -H "Content-Type: application/json"   -d '{"quantityAvailable": 20}'
```

### 4.3 Percorso felice: ordine confermato

Gli esempi usano un prodotto vero del catalogo:

```bash
# id e prezzo di un prodotto a catalogo
curl -s http://localhost:8081/api/products | python -c "import sys,json;p=json.load(sys.stdin)[0];print(p['id'],p['name'],p['price'])"
```

```bash
PID=5   # sostituire con l'id ottenuto sopra
curl -s -H "$AUTH" http://localhost:8083/api/inventory/$PID   # scorte prima

# Nessuna email nella richiesta: l'ordine e' intestato a chi ha il token.
curl -s -X POST http://localhost:8082/api/orders -H "$AUTH"   -H "Content-Type: application/json"   -d "{\"items\":[{\"productId\":$PID,\"productName\":\"Mouse wireless\",\"quantity\":2,\"unitPrice\":29.90}]}"

# dopo un paio di secondi (l'id e' quello restituito sopra)
curl -s -H "$AUTH" http://localhost:8082/api/orders/1        # status: CONFIRMED
curl -s -H "$AUTH" http://localhost:8083/api/inventory/$PID  # 2 pezzi riservati
```

### 4.4 Pagamento rifiutato: compensazione

Il gateway di pagamento simulato rifiuta gli importi da 10.000 in su.
L'ordine viene annullato **e le scorte riservate tornano disponibili**:

```bash
curl -s -X POST http://localhost:8082/api/orders -H "$AUTH"   -H "Content-Type: application/json"   -d '{"items":[{"productId":4,"productName":"Laptop 14\"","quantity":12,"unitPrice":999.99}]}'
```

L'ordine risulta `CANCELLED` con `cancellationReason: PAYMENT_FAILED`,
e le scorte del laptop tornano quelle di partenza.

### 4.5 Scorte insufficienti: saga interrotta subito

```bash
curl -s -X POST http://localhost:8082/api/orders -H "$AUTH"   -H "Content-Type: application/json"   -d '{"items":[{"productId":9,"productName":"Il nome della rosa","quantity":9999,"unitPrice":13.50}]}'
```

L'ordine risulta `CANCELLED` con `cancellationReason:
INVENTORY_REJECTED` e senza che venga creato alcun pagamento: la saga
si ferma al rifiuto delle scorte.

Il motivo dell'annullamento e' il campo su cui il checkout sceglie
cosa dire al cliente — "riduci le quantita'" oppure "riprova il
pagamento" — due rimedi opposti che il generico "ordine annullato" non
permetteva di distinguere.

### 4.6 Vedere gli eventi

Su `http://localhost:8090` (kafka-ui) si possono ispezionare i topic
uno per uno e leggere gli envelope JSON. Tutti gli eventi di una
stessa saga condividono il `correlationId`, quindi il flusso e'
ricostruibile da un capo all'altro. Il topic `saga.dlq` deve restare
vuoto: se contiene qualcosa, un consumer non e' riuscito a processare
un messaggio nemmeno dopo i tentativi previsti.

### 4.7 Dal browser

Con Customer Web (`npm start` in `frontend/customer-web`, vedi 1.4): si
accede con l'utente `customer`, si aggiunge qualcosa al carrello e il
checkout crea l'ordine e ne attende l'esito, mostrando "ordine
confermato" o "ordine annullato" quando la saga si chiude. Servono anche
Catalog Service (8081) per il catalogo e Keycloak per il login.

## 5. Immagini dei prodotti

Un prodotto puo' avere l'immagine in due modi, scelti nel form di Admin
Web:

- **indirizzo esterno**: `imageUrl` contiene un URL assoluto (i prodotti
  dimostrativi usano loremflickr). Nulla viene caricato da noi, e se il
  sito di origine rimuove l'immagine il prodotto resta senza foto;
- **file caricato**: il file va su MinIO e `imageUrl` diventa
  `/api/products/{id}/image`, servito dal Catalog Service.

Nel database non finiscono mai i byte, solo il riferimento. Da riga di
comando:

```bash
ADMIN=$(get_token admin admin-web)

# caricare
curl -s -X POST http://localhost:8081/api/products/15/image   -H "Authorization: Bearer $ADMIN" -F "file=@foto.png;type=image/png"

# rileggere (pubblico, come il resto del catalogo)
curl -s -o scaricata.png http://localhost:8081/api/products/15/image
```

Limite 5 MB, solo tipi `image/*`; il caricamento richiede il ruolo
ADMIN, la lettura no. La console di MinIO e' su
`http://localhost:9001` (`minioadmin`/`minioadmin`).

## 6. Avviare l'infrastruttura Kubernetes con API Gateway

Il deploy e' descritto da un **chart Helm** in
`infrastructure/helm/polyglot-commerce/` (prima era un impianto
base/overlays con Kustomize, sostituito perche' con cinque servizi quasi
identici ogni modifica trasversale andava ripetuta cinque volte - vedi
[infrastructure/helm/README.md](infrastructure/helm/README.md)). Usa la
**Kubernetes Gateway API** e non un Ingress classico, implementata con
**Envoy Gateway**.

Finora il chart e' stato scritto e validato solo localmente (`helm lint`,
`helm template`, `kubectl apply --dry-run=client`), **non ancora
applicato a un cluster reale**: i passi seguenti sono la procedura
prevista, da verificare alla prima esecuzione.

### Prerequisiti

- `kubectl`, `kind`, `helm` (già presenti su questa macchina)

### 6.1 Creare un cluster dedicato

Usa un cluster **dedicato al progetto**, non un cluster kind già in
uso per altro:

```bash
kind create cluster --name polyglot-commerce
kubectl config use-context kind-polyglot-commerce
```

### 6.2 Installare Envoy Gateway (il controller della Gateway API)

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.1.0 \
  -n envoy-gateway-system \
  --create-namespace

kubectl wait --timeout=5m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

### 6.3 Costruire e caricare le immagini dei servizi

Il cluster kind non ha accesso automatico alle immagini Docker locali:
vanno costruite e caricate esplicitamente, una per servizio.

```bash
for svc in catalog-service order-service inventory-service payment-service integration-service; do
  docker build -t "polyglot-commerce/$svc:0.1.0" "services/$svc"
  kind load docker-image "polyglot-commerce/$svc:0.1.0" --name polyglot-commerce
done
```

### 6.4 Installare il chart (valori locali)

```bash
cd infrastructure/helm/polyglot-commerce

# per vedere prima cosa verrebbe applicato, senza applicarlo
helm template polyglot . -f values-local.yaml | less

helm upgrade --install polyglot . -f values-local.yaml
kubectl -n polyglot-commerce get pods
kubectl -n polyglot-commerce get gateway polyglot-commerce-gateway
```

Per tornare indietro dopo un aggiornamento andato male:

```bash
helm history polyglot
helm rollback polyglot <revisione>
```

> **Nota sulle dipendenze**: nel cluster non ci sono (ancora) Postgres,
> Kafka, Keycloak e MinIO. `values-local.yaml` fa puntare ciascun
> servizio a quelli avviati via `docker compose` sull'host, raggiungibili
> da dentro kind come `host.docker.internal:<porta>` (funziona su Docker
> Desktop per Windows/Mac; su Linux puo' servire una configurazione di
> rete diversa). Assicurati quindi che `docker compose up -d` sia attivo
> **prima** di installare il chart.

### 6.5 Esporre il Gateway e testare il routing

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

### 6.6 Pulizia

```bash
kind delete cluster --name polyglot-commerce
```

---

## Struttura del repository

`infrastructure/demo/` contiene gli script che preparano i dati
dimostrativi (oggi solo `seed-stock.sh`, il rifornimento del
magazzino). `infrastructure/minio/` documenta l'object storage delle
immagini.

Vedi la sezione "Repository Structure" in
[polyglot-commerce-platform.md](polyglot-commerce-platform.md) per la
struttura completa prevista a fine progetto.
