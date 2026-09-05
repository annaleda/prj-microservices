# 0003 — Un database per servizio

**Stato**: accettata
**Data**: 2026-08-28

## Contesto

È il pattern fondante dei microservizi: se due servizi condividono le
tabelle non sono indipendenti e non si possono rilasciare separatamente.

La domanda concreta era **quanto** separare: schemi diversi nella stessa
istanza Postgres, oppure istanze distinte.

## Decisione

**Un container Postgres per servizio** — `catalog-db`, `orders-db`,
`payments-db`, `inventory-db`, più `auth-db` usato da Keycloak — e
MongoDB per l'Analytics Service.

## Alternative considerate

- **Un'istanza con più schemi** — più economica in risorse, ma
  l'isolamento diventa una convenzione: basta una `GRANT` sbagliata e
  qualcuno legge le tabelle altrui. Qui la separazione doveva essere
  *visibile*.

## Conseguenze

- **Niente JOIN fra servizi**: i dati si compongono lato applicazione o
  si denormalizzano.
- **Niente transazioni distribuite**: da qui la saga, vedi
  [0007](0007-saga-orchestration-with-camel.md).
- **Denormalizzazione voluta**: una riga d'ordine conserva nome, prezzo
  *e immagine* del prodotto al momento dell'acquisto. Non è ridondanza
  sciatta: un ordine è una ricevuta e deve restare leggibile anche se il
  prodotto viene tolto dal catalogo. La prova sul campo: finché
  l'immagine veniva cercata nel catalogo *attuale*, spariva dagli ordini
  vecchi appena il prodotto veniva cancellato.
- **Il disallineamento fra database è un problema di design.** Il
  magazzino aveva scorte per tre prodotti mentre il catalogo ne aveva
  dodici: ogni ordine sui nuovi veniva annullato. La correzione non è
  stata riallineare i dati ma introdurre l'evento `product.created`.
- Più container da gestire in locale, e più memoria.
