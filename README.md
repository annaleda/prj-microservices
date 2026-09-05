# Polyglot Commerce Platform

Guida per avviare in locale ciò che è stato implementato finora. Per
l'architettura completa vedi
[polyglot-commerce-platform.md](polyglot-commerce-platform.md).

## Cosa c'è

| Servizio | Stack | Porta | Cartella |
|---|---|---|---|
| Catalog Service | Spring Boot 2.7 / Java 11 | 8081 | `services/catalog-service/` |
| Order Service | Spring Boot 2.7 / Java 11 | 8082 | `services/order-service/` |
| Inventory Service | Python / FastAPI | 8083 | `services/inventory-service/` |
| Payment Service | Spring Boot 2.7 / Java 11 | 8084 | `services/payment-service/` |
| Integration Service (saga) | Spring Boot 2.7 + Apache Camel | 8085 | `services/integration-service/` |

| Frontend | Stack | Porta | Cartella |
|---|---|---|---|
| Customer Web (negozio) | Angular 16 | 4200 | `frontend/customer-web/` |
| Admin Web (console) | React + Vite 4 | 5173 | `frontend/admin-web/` |

Infrastruttura via Docker Compose: cinque database Postgres (uno per
servizio), MongoDB, **Kafka**, **Keycloak** e **MinIO**.

> **Auth Service**, **Notification Service** e **Analytics Service** sono
> previsti dal documento di design ma **non implementati**.

---

## 1. Avvio rapido

### Prerequisiti

- Docker Desktop
- Java 11 e Maven 3.8+
- Python 3.12 (per l'Inventory Service)
- Node.js 16 (per i frontend)

> **Nota sulle versioni**: Java 11 e Node 16 non sono una scelta di
> stile, sono ciò che è installato sulla macchina. Da lì derivano Spring
> Boot 2.7 (la 3.x richiede Java 17), Angular 16 e Vite 4 (le versioni
> successive richiedono Node 18+).

### 1.1 Avviare l'infrastruttura

Dalla radice del repository:

```bash
docker compose up -d
docker compose ps
```

Avvia tutto: database, Kafka, Keycloak e MinIO. I container con
healthcheck devono risultare `healthy`; `kafka-init` e `minio-init` sono
"one-shot" e finiscono in `exited (0)` — è normale.

Credenziali e porte sono in [.env](.env).

Per il solo catalogo bastano tre servizi:

```bash
docker compose up -d catalog-db kafka kafka-init minio minio-init
```

Perché servono anche Kafka e MinIO al catalogo:

- **Kafka**: alla creazione di un prodotto il servizio pubblica
  `product.created`. Senza broker raggiungibile la lettura funziona lo
  stesso, ma la creazione resta in attesa finché il producer non
  rinuncia.
- **MinIO**: solo per le immagini caricate; i prodotti con immagine a
  indirizzo esterno funzionano anche senza.

### 1.2 Avviare i servizi

Ogni servizio nel suo terminale:

```bash
cd services/catalog-service     && mvn -s .mvn/settings.xml spring-boot:run   # 8081
cd services/order-service       && mvn -s .mvn/settings.xml spring-boot:run   # 8082
cd services/inventory-service   && python -m uvicorn app.main:app --port 8083 # 8083
cd services/payment-service     && mvn -s .mvn/settings.xml spring-boot:run   # 8084
cd services/integration-service && mvn -s .mvn/settings.xml spring-boot:run   # 8085
```

> **Il flag `-s .mvn/settings.xml` è necessario**: il Maven di questa
> macchina è configurato su repository interni aziendali, raggiungibili
> solo in VPN. Quel file, locale al progetto, punta direttamente a Maven
> Central senza toccare la configurazione condivisa della macchina.

> **Inventory Service**: richiede le dipendenze Python installate
> (`pip install -r requirements.txt`, preferibilmente in un virtualenv).
> Il suo consumer Kafka si può spegnere con `EVENTS_ENABLED=false` per
> usarlo come sola API REST.

Per provare **solo** il catalogo bastano `catalog-db` e Catalog Service:
è il servizio più semplice, senza eventi. Il flusso a eventi completo è
nella [sezione 4](#4-provare-la-saga-order---inventory---payment).

### 1.3 Verificare che funzioni

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/api/products
curl http://localhost:8081/api/categories
```

**Documentazione interattiva delle API** — ogni servizio espone la
propria, con il pulsante *Authorize* per incollare un token (vedi
[sezione 3.2](#32-ottenere-un-token-per-provare-le-api)):

| Servizio | Swagger UI |
|---|---|
| Catalog | `http://localhost:8081/swagger-ui.html` |
| Order | `http://localhost:8082/swagger-ui.html` |
| Payment | `http://localhost:8084/swagger-ui.html` |
| Inventory | `http://localhost:8083/docs` |

L'Integration Service non ha API HTTP: parla solo per eventi.

### 1.4 Avviare Customer Web (il negozio)

```bash
cd frontend/customer-web
npm install
npm start
```

Su `http://localhost:4200`. Il dev server instrada le chiamate `/api/...`
**per prefisso**, verso servizi diversi — lo stesso schema che avrà il
vero API Gateway:

| Prefisso | Servizio |
|---|---|
| `/api/products`, `/api/categories` | Catalog (8081) |
| `/api/orders` | Order (8082) |
| `/api/payments` | Payment (8084) |

La configurazione è in `proxy.conf.json` e **viene letta solo all'avvio**
di `npm start`: modificandola bisogna riavviare il dev server.

> `npm install` usa il registry pubblico grazie al `.npmrc` locale al
> progetto (quello di default della macchina è interno aziendale, non
> raggiungibile da questa rete).

### 1.5 Avviare Admin Web (la console)

```bash
cd frontend/admin-web
npm install
npm run dev
```

Su `http://localhost:5173`. Richiede il login con un utente che abbia il
ruolo **ADMIN** (vedi [sezione 3](#3-autenticazione-con-keycloak)): chi
entra senza quel ruolo vede un messaggio esplicito invece di una console
che risponde 403 a ogni azione.

Da qui si gestiscono prodotti (con immagine e scorte) e ordini. Anche il
suo proxy instrada per prefisso: `/api/inventory` → 8083, `/api/orders`
→ 8082, tutto il resto → 8081.

---

## 2. Kafka

Broker in modalità **KRaft** (senza Zookeeper), già incluso in
`docker-compose.yml`:

```bash
docker compose up -d kafka kafka-init kafka-ui
```

- `kafka`: broker, raggiungibile dagli altri container come `kafka:9092`
  e dall'host come `localhost:9094`.
- `kafka-init`: container "one-shot" che crea i topic dell'event catalog,
  poi termina.
- `kafka-ui`: interfaccia web per ispezionare topic e messaggi, su
  `http://localhost:8090`.

Catalog, Order, Inventory, Payment e Integration Service pubblicano e
consumano questi topic: è su di essi che gira la saga della
[sezione 4](#4-provare-la-saga-order---inventory---payment). Chi produce
e chi consuma cosa è in
[infrastructure/kafka/README.md](infrastructure/kafka/README.md).

---

## 3. Autenticazione con Keycloak

Le API non sono aperte: **Keycloak** emette i token, i servizi ne
verificano la firma con le chiavi pubbliche del realm e decidono in base
ai ruoli. Il catalogo resta l'unica cosa leggibile senza login — è la
vetrina del negozio.

```bash
docker compose up -d keycloak
```

Console su `http://localhost:8180` (`admin`/`admin`), realm
`polyglot-commerce`. Dettagli in
[infrastructure/keycloak/README.md](infrastructure/keycloak/README.md).

### 3.1 Chi può fare cosa

| Operazione | Chi |
|---|---|
| Sfogliare catalogo, categorie e immagini dei prodotti | chiunque, anche senza login |
| Creare e rileggere i propri ordini | `CUSTOMER` |
| Modificare il catalogo | `ADMIN` |
| Caricare l'immagine di un prodotto | `ADMIN` |
| Cambiare a mano lo stato di un ordine | `ADMIN` |
| Eliminare un ordine **annullato** | `ADMIN` |
| Vedere gli ordini di tutti | `ADMIN`, `SUPPORT` |
| Creare pagamenti a mano | `ADMIN` |
| Leggere i pagamenti | `ADMIN`, `SUPPORT` |
| Leggere le scorte | qualunque utente autenticato |
| Rifornire scorte e gestire prenotazioni | `WAREHOUSE`, `ADMIN` |

Un ordine è intestato a **chi presenta il token**: l'email non si manda
nella richiesta, e un cliente non può leggere gli ordini di un altro
(**403**, non 404: l'ordine esiste, semplicemente non è suo). L'identità
è il claim `sub` e non l'email — quest'ultima si può cambiare, e al
momento della registrazione chiunque può dichiarare quella di un altro.

Gli ordini **confermati** non si eliminano: sono la traccia di una
vendita avvenuta. Solo gli annullati, e solo da ADMIN.

Chi non ha un account può **registrarsi** dal pulsante nell'header: la
pagina è quella di Keycloak, con la grafica del negozio, e i nuovi
iscritti ricevono il ruolo `CUSTOMER`.

### 3.2 Ottenere un token per provare le API

Gli utenti demo hanno password uguale allo username:

```bash
get_token() {
  curl -s -X POST http://localhost:8180/realms/polyglot-commerce/protocol/openid-connect/token \
    -d "client_id=$2" -d grant_type=password -d "username=$1" -d "password=$1" \
    | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
}

CUSTOMER=$(get_token customer customer-web)
ADMIN=$(get_token admin admin-web)
WAREHOUSE=$(get_token warehouse admin-web)
```

Verifica veloce che il controllo funzioni:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8083/api/inventory/1                              # 401
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $CUSTOMER" http://localhost:8083/api/inventory/1  # 200
```

Lo stesso token si incolla nel pulsante **Authorize** di Swagger UI, per
provare gli endpoint protetti dal browser.

> Il *password grant* è abilitato **solo** per comodità di prova da riga
> di comando. I frontend non lo usano: fanno authorization code + PKCE,
> e le password non passano mai dall'applicazione.

### 3.3 Dal browser

Customer Web e Admin Web fanno login con Keycloak (authorization code +
PKCE) e allegano da soli il token alle chiamate API. Le pagine di accesso
e registrazione sono servite da Keycloak con un tema dedicato
(`infrastructure/keycloak/themes/polyglot/`) che ne ricalca la grafica.
In Customer Web il catalogo si sfoglia da disconnessi e il login serve al
checkout; Admin Web richiede il ruolo ADMIN per intero.

---

## 4. Provare la saga Order -> Inventory -> Payment

È il flusso a eventi del progetto. L'unica azione richiesta è **creare un
ordine**: riserva delle scorte, pagamento ed esito finale avvengono da
soli, coordinati dall'**Integration Service** (Apache Camel) via Kafka.
Nessuno dei servizi di dominio chiama gli altri.

```text
order.created ---> Inventory Service ---> inventory.reserved --+
      |                                   inventory.rejected   |
      v                                                        v
Integration Service (saga) <---------------------------- payment.requested
      |                                                        |
      +--> order.updated (CONFIRMED)   <--- payment.completed --+
      +--> order.cancelled (+ rilascio scorte) <--- payment.failed
```

### 4.1 Prerequisiti

Infrastruttura e tutti e cinque i servizi avviati
([sezione 1](#1-avvio-rapido)), e i token della
[sezione 3.2](#32-ottenere-un-token-per-provare-le-api).

```bash
AUTH="Authorization: Bearer $CUSTOMER"
```

### 4.2 Rifornire il magazzino

**Passo necessario, non facoltativo.** Un prodotto senza scorte fa
rifiutare la riserva e la saga annulla l'ordine: senza questo passo ogni
acquisto finisce `CANCELLED`.

```bash
sh infrastructure/demo/seed-stock.sh 50    # 50 pezzi per ogni prodotto a catalogo
```

Lo script legge gli identificativi da `GET /api/products` e dichiara le
scorte con `PUT /api/inventory/{id}` usando il token di `warehouse`.
Legge il catalogo invece di conoscerlo: una lista di id scritta a mano si
disallinea appena il catalogo cambia.

Un prodotto creato dopo ottiene subito la sua riga di magazzino — il
Catalog Service pubblica `product.created` e l'Inventory Service la crea
— ma **a zero pezzi**: le unità vanno dichiarate. Da Admin Web c'è il
campo "Scorte disponibili" nel form del prodotto, e l'elenco marca in
rosso quelli a zero (`non ordinabile`). Via API:

```bash
curl -s -X PUT http://localhost:8083/api/inventory/5 \
  -H "Authorization: Bearer $WAREHOUSE" -H "Content-Type: application/json" \
  -d '{"quantityAvailable": 20}'
```

### 4.3 Percorso felice: ordine confermato

Gli esempi usano un prodotto vero, letto dal catalogo:

```bash
# primo prodotto a catalogo
PID=$(curl -s http://localhost:8081/api/products \
  | python -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")

# corpo dell'ordine costruito da Python: i nomi dei prodotti contengono
# spazi e virgolette (es. Laptop 14"), che comporli a mano romperebbe
BODY=$(curl -s http://localhost:8081/api/products | python -c "
import sys, json
p = json.load(sys.stdin)[0]
print(json.dumps({'items': [{'productId': p['id'], 'productName': p['name'],
                             'quantity': 2, 'unitPrice': p['price']}]}))")

# scorte prima
curl -s -H "$AUTH" http://localhost:8083/api/inventory/$PID

# crea l'ordine; nessuna email: è intestato a chi ha il token
OID=$(curl -s -X POST http://localhost:8082/api/orders -H "$AUTH" \
  -H "Content-Type: application/json" -d "$BODY" \
  | python -c "import sys,json;print(json.load(sys.stdin)['id'])")

# dopo un paio di secondi la saga si è chiusa
sleep 3
curl -s -H "$AUTH" http://localhost:8082/api/orders/$OID    # status: CONFIRMED
curl -s -H "$AUTH" http://localhost:8083/api/inventory/$PID # 2 pezzi riservati
```

### 4.4 Pagamento rifiutato: compensazione

Il gateway di pagamento è **simulato** e rifiuta gli importi da 10.000 in
su. L'ordine viene annullato **e le scorte riservate tornano
disponibili**.

L'ordine deve superare la soglia **restando dentro le scorte
disponibili**: se chiedi più pezzi di quelli che ci sono, viene rifiutato
prima dal magazzino e il pagamento non viene mai richiesto (è lo scenario
4.5, non questo). Quindi si prende il prodotto **più caro** e si calcola
la quantità minima che supera i 10.000:

```bash
BIG=$(curl -s http://localhost:8081/api/products | python -c "
import sys, json, math
p = max(json.load(sys.stdin), key=lambda x: x['price'])
qta = math.ceil(10000 / p['price'])
print(json.dumps({'items': [{'productId': p['id'], 'productName': p['name'],
                             'quantity': qta, 'unitPrice': p['price']}]}))")
echo "$BIG"

curl -s -X POST http://localhost:8082/api/orders -H "$AUTH" \
  -H "Content-Type: application/json" -d "$BIG"
```

L'ordine risulta `CANCELLED` con `cancellationReason: PAYMENT_FAILED`, e
le scorte tornano quelle di partenza — è la **compensazione** della saga.

> Se anche così esce `INVENTORY_REJECTED`, vuol dire che quel prodotto
> non ha abbastanza pezzi: rifornisci con
> `sh infrastructure/demo/seed-stock.sh 50`.

### 4.5 Scorte insufficienti: saga interrotta subito

```bash
TROPPI=$(curl -s http://localhost:8081/api/products | python -c "
import sys, json
p = json.load(sys.stdin)[0]
print(json.dumps({'items': [{'productId': p['id'], 'productName': p['name'],
                             'quantity': 99999, 'unitPrice': p['price']}]}))")

curl -s -X POST http://localhost:8082/api/orders -H "$AUTH" \
  -H "Content-Type: application/json" -d "$TROPPI"
```

L'ordine risulta `CANCELLED` con `cancellationReason: INVENTORY_REJECTED`
e **senza che venga creato alcun pagamento**: la saga si ferma al rifiuto
delle scorte.

Il motivo dell'annullamento è il campo su cui il checkout sceglie cosa
dire al cliente — "riduci le quantità" oppure "riprova il pagamento" —
due rimedi opposti che il generico "ordine annullato" non permetteva di
distinguere.

### 4.6 Vedere gli eventi

Su `http://localhost:8090` (kafka-ui) si ispezionano i topic e si leggono
gli envelope JSON. Tutti gli eventi di una stessa saga condividono il
`correlationId`, quindi il flusso è ricostruibile da un capo all'altro.

Il topic `saga.dlq` **deve restare vuoto**: se contiene qualcosa, un
consumer non è riuscito a processare un messaggio nemmeno dopo i
tentativi previsti.

### 4.7 Dal browser

Con Customer Web ([sezione 1.4](#14-avviare-customer-web-il-negozio)): si
accede come `customer`, si aggiunge qualcosa al carrello e il checkout
crea l'ordine e ne attende l'esito, mostrando "confermato" o "annullato"
**con il motivo** quando la saga si chiude. Gli ordini annullati non
compaiono nello storico del cliente: si vedono solo dalla console admin,
con il cliente e gli articoli accanto al motivo, così si capisce cosa
rifornire.

---

## 5. Immagini dei prodotti

Un prodotto può avere l'immagine in due modi, scelti nel form di Admin
Web:

- **indirizzo esterno**: `imageUrl` contiene un URL assoluto (i prodotti
  dimostrativi usano loremflickr). Nulla viene caricato da noi, e se il
  sito di origine rimuove l'immagine il prodotto resta senza foto;
- **file caricato**: il file va su MinIO e `imageUrl` diventa
  `/api/products/{id}/image`, servito dal Catalog Service.

Nel database non finiscono mai i byte, solo il riferimento. E il percorso
salvato è **relativo**, non l'indirizzo di MinIO: quello cambia fra
locale e cluster e resterebbe congelato in ogni riga già scritta.

Da riga di comando:

```bash
# caricare (richiede ADMIN)
curl -s -X POST http://localhost:8081/api/products/5/image \
  -H "Authorization: Bearer $ADMIN" -F "file=@foto.png;type=image/png"

# rileggere (pubblico, come il resto del catalogo)
curl -s -o scaricata.png http://localhost:8081/api/products/5/image
```

Limite 5 MB, solo tipi `image/*`. La console di MinIO è su
`http://localhost:9001` (`minioadmin`/`minioadmin`); dettagli in
[infrastructure/minio/README.md](infrastructure/minio/README.md).

---

## 6. Osservabilità

Metriche, grafici e tracing distribuito. Dettagli e trappole in
[infrastructure/monitoring/README.md](infrastructure/monitoring/README.md).

```bash
docker compose up -d prometheus grafana jaeger
```

| Strumento | Indirizzo | |
|---|---|---|
| Prometheus | `http://localhost:9090` | metriche |
| Grafana | `http://localhost:3000` | `admin`/`admin`, datasource già pronte |
| Jaeger | `http://localhost:16686` | trace distribuite |

Le metriche funzionano senza fare altro: i servizi le espongono su
`/actuator/prometheus` (Spring) e `/metrics` (FastAPI), e Prometheus le
raccoglie ogni 15 secondi.

Per le **trace** serve avviare i servizi con la strumentazione. Scarica
una volta l'agent:

```bash
sh infrastructure/monitoring/download-otel-agent.sh
```

Poi i servizi Java — nessuna modifica al codice, l'agent li strumenta a
runtime:

```bash
AG=infrastructure/monitoring/opentelemetry-javaagent.jar
OTEL="-Dotel.exporter.otlp.endpoint=http://localhost:4318 -Dotel.metrics.exporter=none -Dotel.logs.exporter=none"

java -javaagent:$AG -Dotel.service.name=order-service $OTEL      -jar services/order-service/target/order-service.jar
```

E quello Python:

```bash
cd services/inventory-service
pip install -r requirements-otel.txt

OTEL_SERVICE_NAME=inventory-service OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none opentelemetry-instrument python -m uvicorn app.main:app --port 8083
```

Creando un ordine, su Jaeger compare una trace che attraversa **tre
servizi passando da Kafka**: `order-service` pubblica e, 31ms dopo,
`inventory-service` e `integration-service` raccolgono lo stesso evento.

> **Limite noto**: la saga non è una trace sola. Il tratto del pagamento
> finisce in una trace separata, perché Camel apre un nuovo scambio per
> ogni rotta. Per ricucire i segmenti resta il `correlationId`.

---

## 7. Deploy su Kubernetes con API Gateway

Il deploy è descritto da un **chart Helm** in
`infrastructure/helm/polyglot-commerce/` (prima era un impianto
base/overlays con Kustomize, sostituito perché con cinque servizi quasi
identici ogni modifica trasversale andava ripetuta cinque volte — vedi
[infrastructure/helm/README.md](infrastructure/helm/README.md)). Usa la
**Kubernetes Gateway API**, non un Ingress classico, implementata con
**Envoy Gateway**.

> Il chart è stato scritto e **validato** (`helm lint`, `helm template`
> confrontato oggetto per oggetto con il precedente setup Kustomize,
> `kubectl apply --dry-run=client`) ma **mai applicato a un cluster
> reale**. I passi seguenti sono la procedura prevista, da verificare
> alla prima esecuzione.

### Prerequisiti

`kubectl`, `kind`, `helm`.

### 7.1 Creare un cluster dedicato

Usa un cluster **dedicato al progetto**, non uno già in uso per altro:

```bash
kind create cluster --name polyglot-commerce
kubectl config use-context kind-polyglot-commerce
```

### 7.2 Installare Envoy Gateway (il controller della Gateway API)

Non fa parte del chart: è infrastruttura di cluster, non applicativa.

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.1.0 -n envoy-gateway-system --create-namespace

kubectl wait --timeout=5m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

### 7.3 Costruire e caricare le immagini

kind non accede alle immagini Docker locali: vanno caricate
esplicitamente.

```bash
for svc in catalog-service order-service inventory-service payment-service integration-service; do
  docker build -t "polyglot-commerce/$svc:0.1.0" "services/$svc"
  kind load docker-image "polyglot-commerce/$svc:0.1.0" --name polyglot-commerce
done
```

### 7.4 Installare il chart

```bash
cd infrastructure/helm/polyglot-commerce

# vedere cosa verrebbe applicato, senza applicarlo
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

> **Nota sulle dipendenze**: nel cluster non ci sono Postgres, Kafka,
> Keycloak e MinIO. `values-local.yaml` fa puntare ciascun servizio a
> quelli avviati via `docker compose` sull'host, raggiungibili da dentro
> kind come `host.docker.internal:<porta>` (funziona su Docker Desktop
> per Windows/Mac; su Linux può servire una configurazione di rete
> diversa). Assicurati che `docker compose up -d` sia attivo **prima** di
> installare il chart.

### 7.5 Esporre il Gateway e testare il routing

Envoy Gateway crea un `Service` per il listener HTTP del `Gateway`. Su
kind (senza LoadBalancer reale) si usa `port-forward`:

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

Se funziona, le richieste passano: client → Gateway (Envoy) →
`HTTPRoute` → Service → Pod.

> Solo `/api/products` e `/api/categories` rispondono 200 senza token:
> sulle altre rotte un **401 è già un esito corretto**, vuol dire che la
> richiesta ha raggiunto il servizio ed è stata respinta dal controllo di
> sicurezza, non persa dal gateway.
>
> Per ottenere 200 serve un token valido, e lì entra in gioco
> l'avvertenza sull'issuer scritta in `values-local.yaml`: quell'URL non
> serve solo a *raggiungere* Keycloak, decide anche quale `iss` è
> accettabile nel token. Se il browser prende i token da un indirizzo e
> il servizio se ne aspetta un altro, li rifiuta tutti pur essendo la
> firma valida.

### 7.6 Pulizia

```bash
kind delete cluster --name polyglot-commerce
```

---

## Struttura del repository

```text
services/           i cinque microservizi
frontend/           customer-web (Angular) e admin-web (React)
infrastructure/
  helm/             chart di deploy su Kubernetes
  kafka/            topic dell'event catalog, chi produce e chi consuma
  keycloak/         realm importabile, tema delle pagine di login
  minio/            object storage delle immagini
  monitoring/       Prometheus, Grafana, agent OpenTelemetry
  demo/             script per i dati dimostrativi (seed-stock.sh)
docs/adr/           12 decisioni architetturali, con il perché
.github/workflows/  pipeline di CI (path filtering, test, build)
docker-compose.yml  infrastruttura locale
```

Per la struttura completa prevista a fine progetto vedi la sezione
"Repository Structure" in
[polyglot-commerce-platform.md](polyglot-commerce-platform.md).

## Test

```bash
# servizi Java (Catalog, Order, Payment, Integration)
cd services/<nome> && mvn -s .mvn/settings.xml test

# Inventory Service — dal virtualenv, dove stanno le dipendenze di test
cd services/inventory-service && python -m pytest

# Customer Web — senza watch, come in CI
cd frontend/customer-web && npm test -- --watch=false --browsers=ChromeHeadless
```

I test di integrazione usano **Testcontainers**: Postgres, Kafka e MinIO
**veri** avviati in Docker, non mock. Serve quindi Docker attivo, e la
prima esecuzione scarica le immagini.

Conteggio attuale: Catalog 10, Order 20, Payment 9, Integration 5,
Inventory 18, Customer Web 20.

> **Admin Web non ha test**: è l'unica parte del progetto senza, ed è
> segnalato fra le cose da sistemare.
