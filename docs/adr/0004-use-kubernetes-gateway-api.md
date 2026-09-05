# 0004 — Kubernetes Gateway API invece di Ingress

**Stato**: accettata
**Data**: 2026-08-28

## Contesto

I servizi vanno esposti verso l'esterno da un unico punto d'ingresso, che
instrada per percorso: `/api/products` al catalogo, `/api/orders` agli
ordini, e così via.

Lo strumento storico di Kubernetes per questo è `Ingress`.

## Decisione

**Gateway API**, implementata con **Envoy Gateway**.

Il chart crea `GatewayClass`, `Gateway` e un `HTTPRoute` per servizio; il
*controller* non ne fa parte, perché è infrastruttura di cluster e si
installa a parte.

## Alternative considerate

- **Ingress** — funziona, ma ha tre limiti noti: è poco espressivo (host
  e path, poco altro); ogni controller aggiunge le **proprie
  annotazioni**, quindi un Ingress davvero portabile non esiste; e mette
  chi gestisce il cluster e chi sviluppa l'applicazione a scrivere nella
  **stessa risorsa**.

## Conseguenze

- La ragione principale non è "è più nuovo", è la **separazione dei
  ruoli**: `GatewayClass` la scrive chi fornisce l'infrastruttura,
  `Gateway` chi gestisce il cluster, `HTTPRoute` chi sviluppa
  l'applicazione. Il modello di permessi rispecchia come sono organizzati
  i team.
- Routing su header, metodo e query, e traffic splitting per peso
  (canary) sono **nella specifica**, non in annotazioni proprietarie.
- Costo: le CRD della Gateway API devono essere installate nel cluster,
  quindi `kubectl apply --dry-run=client` **non riesce a validare**
  `Gateway`, `GatewayClass` e `HTTPRoute` senza di esse. È il motivo per
  cui la validazione locale copre solo gli oggetti standard.
- L'Integration Service **non ha HTTPRoute**: parla solo per eventi.
