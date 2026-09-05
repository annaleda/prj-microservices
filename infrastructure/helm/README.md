# Helm

Il deploy dei microservizi su Kubernetes è descritto da un unico chart:
[`polyglot-commerce/`](polyglot-commerce/).

```bash
cd infrastructure/helm/polyglot-commerce

helm lint .
helm template polyglot . -f values-local.yaml     # vedere cosa verrebbe applicato
helm upgrade --install polyglot . -f values-local.yaml
```

## Perché Helm e non più Kustomize

Il progetto è partito con Kustomize (base + overlay). Funzionava, ma con
cinque servizi quasi identici l'impianto mostrava il fianco: ogni
servizio aveva **cinque file suoi** (ConfigMap, Secret, Deployment,
Service, HTTPRoute) praticamente uguali agli altri, e ogni modifica
trasversale — aggiungere `KAFKA_BOOTSTRAP_SERVERS`, cambiare una probe —
andava ripetuta a mano cinque volte. È successo davvero più di una volta.

Con Helm quei file sono diventati **un template solo per tipo**, che
cicla sui servizi definiti in `values.yaml`:

```yaml
services:
  order-service:
    port: 8082
    probes: spring
    routes: [/api/orders]
    database: { envPrefix: ORDERS, name: orders, ... }
```

Aggiungere un servizio ora è una decina di righe in `values.yaml`, non
una cartella nuova.

Le differenze che contano, riassunte:

| | Kustomize | Helm |
|---|---|---|
| Come varia | patch su YAML esistente | template con variabili |
| Ripetizione | va gestita duplicando | cicli e `range` |
| Condizionali | non ce ne sono | `if`, `with`, `default` |
| Versionamento | nessuno | chart versionato, `helm history`, `helm rollback` |
| Distribuzione | copiando file | repository di chart |
| Curva | bassa, YAML puro | più alta, template Go |

Il vantaggio operativo più concreto è il **rollback**: `helm rollback`
riporta il rilascio allo stato precedente, cosa che con Kustomize
significa ritrovare il commit giusto e riapplicare.

Il vantaggio più utile nel quotidiano è invece questo:

```yaml
annotations:
  checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") $ | sha256sum }}
```

Cambiando **solo** una ConfigMap, cambia la checksum, quindi cambia il
template del pod, quindi Kubernetes fa ripartire i pod. Senza, un
`helm upgrade` che tocca solo la configurazione non riavvia nulla e i pod
continuano con i valori vecchi — un classico "ho cambiato la config e non
succede niente".

> Non è che Helm sia "meglio" in assoluto: per poche risorse e differenze
> minime fra ambienti, Kustomize è più semplice e non introduce un
> linguaggio di template. Molti team li usano insieme (Helm per il
> pacchetto, Kustomize per le ultime patch locali).

## I file dei valori

| File | Quando |
|---|---|
| `values.yaml` | default: tutto dentro il cluster (database, Kafka, Keycloak, MinIO raggiungibili per nome) |
| `values-local.yaml` | servizi in kind, dipendenze su docker-compose sull'host (`host.docker.internal`) |
| `values-production.yaml` | **segnaposto**: elenca cosa manca (registry, secret manager, dominio) invece di fingere di essere pronto |

Si applicano in cascata: `-f values-local.yaml` sovrascrive solo le
chiavi che nomina, il resto resta quello di `values.yaml`.

## Cosa il chart non fa

- **Non installa il controller Envoy Gateway.** È infrastruttura di
  cluster, non applicativa, e si installa a parte:

  ```bash
  helm install eg oci://docker.io/envoyproxy/gateway-helm \
    --version v1.1.0 -n envoy-gateway-system --create-namespace
  ```

  Il chart crea `GatewayClass` e `Gateway`; in un'organizzazione con
  ruoli separati li crea la piattaforma e qui si mette
  `gateway.enabled: false`, lasciando solo gli `HTTPRoute`.

- **Non installa database, Kafka, Keycloak o MinIO.** In locale girano su
  docker-compose; in un cluster vero sarebbero chart o operator a parte.

- **Non gestisce i segreti sul serio.** I default sono credenziali di
  sviluppo in chiaro, allineate a `.env`. Fuori dal locale servono
  External Secrets Operator, Vault o il secret manager del cloud.

## Stato della verifica

Il chart è stato **validato ma mai applicato a un cluster**:

- `helm lint` pulito;
- `helm template` confrontato oggetto per oggetto con l'output del
  precedente setup Kustomize: **stessi 26 oggetti, stesse ConfigMap,
  stessi Secret, stessi Deployment** (repliche, immagini, porte, probe);
- `kubectl apply --dry-run=client` valida tutto tranne `Gateway`,
  `GatewayClass` e `HTTPRoute`, che richiedono le CRD della Gateway API
  installate nel cluster.

Il deploy vero su un cluster kind resta da fare, ed è il punto in cui
emergerà la questione dell'issuer di Keycloak descritta in
`values-local.yaml`.
