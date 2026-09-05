# 0001 — Monorepo invece di un repository per servizio

**Stato**: accettata
**Data**: 2026-08-28

## Contesto

Il progetto comprende cinque microservizi in due linguaggi, due frontend,
la configurazione di Kubernetes e quella dell'infrastruttura locale. La
scelta canonica nei microservizi è **un repository per servizio**, perché
rafforza l'indipendenza dei rilasci.

Questo però è un progetto **dimostrativo e didattico**, sviluppato da una
persona sola, il cui scopo è mostrare come i pezzi si incastrano.

## Decisione

Un **monorepo**, con `services/`, `frontend/` e `infrastructure/` come
cartelle di primo livello.

## Conseguenze

**A favore**

- Una modifica che attraversa più servizi (per esempio l'aggiunta del
  campo `imageUrl` alla riga d'ordine, che tocca backend e due frontend)
  si vede in un commit solo.
- L'infrastruttura sta accanto a ciò che descrive.
- Chi apre il progetto capisce l'insieme senza rincorrere sette
  repository.

**Contro, e vanno detti**

- L'indipendenza dei rilasci non è imposta dallo strumento ma dalla
  disciplina: nulla impedisce fisicamente a un servizio di importare il
  codice di un altro.
- La CI deve usare il **path filtering** per non ricostruire tutto a ogni
  commit.
- Su una base di codice grande e un team numeroso il monorepo richiede
  strumenti dedicati (Bazel, Nx, Turborepo) che qui sarebbero
  sproporzionati.

**Mitigazione adottata**: i servizi non condividono **alcuna libreria di
modelli**. Ognuno ha la propria copia di `EventEnvelope`, e quello Python
la ricostruisce a mano. Il contratto fra servizi è il **JSON sul topic**,
non una classe Java — vedi [0002](0002-use-kafka.md).
