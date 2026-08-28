# Polyglot Commerce Platform

Guida rapida per avviare in locale ciò che è stato implementato finora.
Per l'architettura completa vedi
[polyglot-commerce-platform.md](polyglot-commerce-platform.md).

---

## 1. Avviare il Catalog Service in locale

Il primo (e finora unico) microservizio implementato è **Catalog
Service** (Spring Boot 2.7 / Java 11, `services/catalog-service/`).

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

> Nessun microservizio pubblica o consuma eventi ancora: per ora
> questa è solo l'infrastruttura. Il collegamento reale (Saga
> Orchestration via Integration Service/Apache Camel) è un passo
> successivo — vedi [infrastructure/kafka/README.md](infrastructure/kafka/README.md).

## 3. Avviare l'infrastruttura Kubernetes con API Gateway

I manifest si trovano in `infrastructure/kubernetes/` (pattern
base/overlays con Kustomize) e usano la **Kubernetes Gateway API**
(non un Ingress classico), implementata con **Envoy Gateway**. Finora
sono stati scritti e validati solo localmente (`kubectl kustomize` +
dry-run), **non ancora testati su un cluster reale** in questa
sessione: i passi seguenti sono la procedura prevista, da verificare
alla prima esecuzione.

### Prerequisiti

- `kubectl`, `kind`, `helm` (già presenti su questa macchina)

### 3.1 Creare un cluster dedicato

Usa un cluster **dedicato al progetto**, non un cluster kind già in
uso per altro:

```bash
kind create cluster --name polyglot-commerce
kubectl config use-context kind-polyglot-commerce
```

### 3.2 Installare Envoy Gateway (il controller della Gateway API)

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.1.0 \
  -n envoy-gateway-system \
  --create-namespace

kubectl wait --timeout=5m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

### 3.3 Costruire e caricare le immagini dei servizi

Il cluster kind non ha accesso automatico alle immagini Docker locali:
vanno costruite e caricate esplicitamente, una per servizio.

```bash
for svc in catalog-service order-service inventory-service payment-service; do
  docker build -t "polyglot-commerce/$svc:0.1.0" "services/$svc"
  kind load docker-image "polyglot-commerce/$svc:0.1.0" --name polyglot-commerce
done
```

### 3.4 Applicare i manifest (overlay locale)

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

### 3.5 Esporre il Gateway e testare il routing

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

### 3.6 Pulizia

```bash
kind delete cluster --name polyglot-commerce
```

---

## Struttura del repository

Vedi la sezione "Repository Structure" in
[polyglot-commerce-platform.md](polyglot-commerce-platform.md) per la
struttura completa prevista a fine progetto.
