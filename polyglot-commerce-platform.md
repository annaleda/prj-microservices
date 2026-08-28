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

## 5. Backend Services

### Auth Service

**Stack:** Java, Spring Boot, Spring Security, OAuth2/OIDC, PostgreSQL.

Responsabilità: profili utenti, ruoli, autorizzazioni applicative e
integrazione con Keycloak.

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
```

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
POST /api/inventory/reservations
DELETE /api/inventory/reservations/{id}
```

Eventi:

``` text
order.created
order.cancelled
inventory.reserved
inventory.rejected
inventory.released
```

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

1. L'Integration Service consuma `order.created`.
2. Invoca l'Inventory Service e attende `inventory.reserved` /
   `inventory.rejected`.
3. Se la riserva ha successo, richiede il pagamento pubblicando
   `payment.requested` e attende `payment.completed` /
   `payment.failed` dal Payment Service.
4. In base all'esito complessivo pubblica `order.updated` (successo) o
   `order.cancelled` (fallimento).
5. In caso di fallimento in un punto qualsiasi della sequenza, esegue
   le **compensazioni**: rilascio della riserva
   (`inventory.released`) e, se il pagamento era già stato addebitato,
   avvio del rimborso.

Questo modello centralizza la logica di coordinamento e le
compensazioni in un unico componente, mantenendo i servizi di dominio
(Order, Inventory, Payment) semplici e privi di conoscenza reciproca.

## 9. Authentication & Authorization

Identity Provider: **Keycloak**

Protocolli: - OAuth 2.0 - OpenID Connect - JWT

Ruoli: - CUSTOMER - ADMIN - WAREHOUSE - SUPPORT

Comunicazione service-to-service: i servizi interni si autenticano tra
loro tramite **OAuth2 Client Credentials Grant** verso Keycloak,
ottenendo un token applicativo dedicato (distinto dal token utente)
per ogni chiamata sincrona tra microservizi.

## 10. Docker e Kubernetes

Ogni applicazione possiede il proprio `Dockerfile`.

In locale l'infrastruttura potrà essere eseguita con Docker Compose.

Kubernetes utilizzerà: - Deployment - Service - ConfigMap - Secret -
HorizontalPodAutoscaler - GatewayClass - Gateway - HTTPRoute

Struttura:

``` text
infrastructure/kubernetes/
├── base/
│   ├── auth-service/
│   ├── catalog-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── inventory-service/
│   ├── integration-service/
│   └── analytics-service/
└── overlays/
    ├── local/
    ├── staging/
    └── production/
```

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
