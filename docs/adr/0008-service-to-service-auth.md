# 0008 — Autenticazione fra servizi

**Stato**: **rimandata** (nessuna implementazione)
**Data**: 2026-09-05

## Contesto

Il documento di design prevede che i servizi interni si autentichino fra
loro con **OAuth2 Client Credentials Grant**, ottenendo da Keycloak un
token applicativo distinto da quello utente.

## Decisione

**Non implementarlo ora.**

Nessun servizio chiama un altro via HTTP: comunicano **solo per eventi**
([0007](0007-saga-orchestration-with-camel.md)). Un client per il
Client Credentials Grant sarebbe oggi un insieme di credenziali senza
alcun uso — e credenziali inutilizzate sono superficie d'attacco a costo
zero di beneficio.

## Conseguenze

- **Kafka resta il punto scoperto.** Nel progetto il broker è senza
  autenticazione: chiunque raggiunga la rete può pubblicare su qualsiasi
  topic. In un ambiente reale servirebbero SASL/mTLS e ACL per topic. È
  una lacuna consapevole di un ambiente locale, non una svista.
- La decisione va **riaperta** al primo di questi eventi:
  - un servizio deve chiamarne un altro in modo sincrono;
  - si integra un sistema esterno che richiede un'identità applicativa;
  - il deploy esce dal locale.
- Nel frattempo l'autorizzazione è tutta **sul token dell'utente**: ogni
  servizio legge i ruoli dal JWT e decide da sé.
