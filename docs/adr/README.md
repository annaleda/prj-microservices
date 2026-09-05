# Architecture Decision Records

Un **ADR** registra una decisione architetturale: il contesto in cui è
stata presa, cosa si è deciso, quali alternative sono state scartate e
cosa comporta.

Il codice mostra *cosa* è stato fatto, non *perché*. Queste sono le
domande a cui il codice non risponde: perché la saga è orchestrata e non
coreografata, perché cinque database Postgres distinti invece di cinque
schemi, perché la Gateway API invece di un Ingress.

## Come si usano

- **Sono immutabili.** Una decisione che si rivela sbagliata non si
  modifica: si scrive un ADR nuovo che la *supersede*, e il vecchio resta
  a testimoniare cosa si sapeva allora.
- **Sono corti.** Una pagina. Se serve un trattato, la decisione non è
  ancora chiara.
- **Si scrivono quando la decisione si prende**, non a posteriori — con
  l'eccezione di questo primo blocco, scritto insieme per recuperare il
  pregresso.

Formato: quello di Michael Nygard — Contesto, Decisione, Conseguenze.

## Stato

| # | Decisione | Stato |
|---|---|---|
| [0001](0001-use-monorepo.md) | Monorepo invece di un repository per servizio | Accettata |
| [0002](0002-use-kafka.md) | Kafka come event backbone | Accettata |
| [0003](0003-database-per-service.md) | Un database per servizio | Accettata |
| [0004](0004-use-kubernetes-gateway-api.md) | Kubernetes Gateway API invece di Ingress | Accettata |
| [0005](0005-use-keycloak.md) | Keycloak come identity provider | Accettata |
| [0006](0006-use-apache-camel.md) | Apache Camel per l'integrazione | Accettata |
| [0007](0007-saga-orchestration-with-camel.md) | Saga orchestrata, non coreografata | Accettata |
| [0008](0008-service-to-service-auth.md) | Autenticazione fra servizi | Rimandata |
| [0009](0009-helm-invece-di-kustomize.md) | Helm al posto di Kustomize | Accettata, supersede parte della 0004 |
| [0010](0010-object-storage-per-le-immagini.md) | Object storage per le immagini dei prodotti | Accettata |
| [0011](0011-identita-ordine-su-claim-sub.md) | La proprietà di un ordine si basa sul claim `sub` | Accettata |
| [0012](0012-versioni-vincolate-dall-ambiente.md) | Versioni di Java e Node vincolate dalla macchina | Accettata |
