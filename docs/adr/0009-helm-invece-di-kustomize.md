# 0009 — Helm al posto di Kustomize

**Stato**: accettata — supersede la parte di [0004](0004-use-kubernetes-gateway-api.md) sull'impianto dei manifest
**Data**: 2026-09-05

## Contesto

Il deploy era descritto con **Kustomize**, nel pattern base + overlays.
Funzionava, ma con cinque servizi quasi identici ogni servizio aveva
**cinque file suoi** — ConfigMap, Secret, Deployment, Service, HTTPRoute
— praticamente uguali agli altri.

Ogni modifica trasversale andava ripetuta cinque volte, ed **è successo
davvero**: prima aggiungendo `KAFKA_BOOTSTRAP_SERVERS`, poi la
configurazione di MinIO.

## Decisione

Un **chart Helm** con **un template per tipo di risorsa** che cicla su
`.Values.services`. Le differenze fra i servizi (porta, stile delle
probe, rotte, database, funzionalità attive) stanno tutte in
`values.yaml`.

## Alternative considerate

- **Restare su Kustomize** — resta YAML puro e non introduce un
  linguaggio di template. Sarebbe la scelta giusta con poche risorse e
  differenze minime fra ambienti: qui la ripetizione aveva superato la
  soglia.
- **Usarli insieme** (`helm template | kubectl apply -k`) — sensato in
  organizzazioni dove la piattaforma distribuisce chart e i team
  applicano patch locali. Qui sarebbe complessità senza beneficio.

## Conseguenze

- Aggiungere un microservizio è **una decina di righe** in `values.yaml`,
  non una cartella di manifest.
- Si guadagnano `helm history` e `helm rollback`: con Kustomize tornare
  indietro significa ritrovare il commit giusto e riapplicare.
- Si guadagna l'annotazione **`checksum/config`**: cambiando *solo* una
  ConfigMap cambia la checksum, quindi il pod riparte. Senza, un
  `helm upgrade` che tocca solo la configurazione non riavvia nulla e i
  pod restano con i valori vecchi.
- Si perde la leggibilità del YAML puro: i template Go vanno letti, e
  `$` contro `.` dentro un `range` è una fonte classica di errori.
- **La migrazione è stata verificata per equivalenza**, non a occhio:
  `helm template` confrontato oggetto per oggetto con l'output di
  `kubectl kustomize` — stessi 26 oggetti, stesse ConfigMap, stessi
  Secret, stessi Deployment. Solo dopo il vecchio impianto è stato
  rimosso.
