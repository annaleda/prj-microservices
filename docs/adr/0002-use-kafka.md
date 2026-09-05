# 0002 — Kafka come event backbone

**Stato**: accettata
**Data**: 2026-08-28

## Contesto

I servizi devono comunicare senza conoscersi. Servivano: consegna
affidabile, più consumatori indipendenti sullo stesso flusso, e la
possibilità di rileggere ciò che è successo.

## Decisione

**Apache Kafka**, in modalità **KRaft** (senza Zookeeper).

Il contratto fra servizi è il **JSON sul topic**, non una libreria
condivisa: ogni servizio mantiene la propria copia della classe
`EventEnvelope`, e quello Python la costruisce a mano.

## Alternative considerate

- **RabbitMQ** — ottimo per routing complesso per messaggio e code di
  lavoro, ma i messaggi vengono consumati e spariscono. Qui `order.created`
  serve a *due* consumatori indipendenti (Inventory e Integration), e
  poter rileggere il log è utile in un progetto di studio.
- **Chiamate REST sincrone** — accoppiano i servizi nel tempo: se
  l'Inventory è giù, l'ordine non si può creare.
- **Zookeeper** invece di KRaft — un componente in più da gestire, e
  ormai deprecato per le installazioni nuove.

## Conseguenze

- L'ordine dei messaggi è garantito **solo dentro una partizione**: la
  chiave è l'id dell'ordine, così gli eventi della stessa saga restano
  ordinati fra loro e ordini diversi procedono in parallelo.
- La consegna è **at-least-once**: i duplicati sono normali e vanno resi
  innocui con consumatori **idempotenti**, non evitati.
- Serve una **dead letter queue** (`saga.dlq`): senza, un messaggio non
  processabile bloccherebbe la partizione riproponendosi all'infinito.
- Condividere una libreria di modelli fra servizi li riaccoppierebbe
  dalla porta di servizio: da qui la duplicazione voluta dell'envelope.
- Resta aperto il problema del **dual write**: si pubblica dopo il commit
  della transazione, il che evita di annunciare qualcosa che un rollback
  fa sparire, ma non copre "commit riuscito, pubblicazione fallita". La
  soluzione completa è il **Transactional Outbox**, non implementato.
