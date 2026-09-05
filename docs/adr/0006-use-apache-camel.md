# 0006 — Apache Camel per l'integrazione

**Stato**: accettata
**Data**: 2026-09-05

## Contesto

Serviva un componente che consumasse eventi da Kafka, decidesse il passo
successivo e ne pubblicasse un altro, con gestione degli errori e retry.

## Decisione

**Apache Camel 3.22** su Spring Boot 2.7 (la serie 4 richiede Boot 3),
con cinque rotte tutte della stessa forma: consuma un evento, chiedi
all'orchestratore il passo successivo, pubblicalo.

**La logica di coordinamento non conosce Camel**: `SagaOrchestrator` è
una classe Spring normale che riceve JSON e restituisce "il prossimo
evento"; le rotte fanno solo da collegamento.

## Alternative considerate

- **Spring Kafka da solo** — sarebbe bastato per questi cinque passi. Ma
  il documento di design pone Camel fra le tecnologie da dimostrare, e il
  suo valore emerge quando i sistemi da integrare diventano eterogenei
  (FTP, S3, HTTP, mail): oltre 300 componenti con la stessa forma.
- **Kafka Streams** — è per *elaborare* flussi (join, finestre,
  aggregazioni), non per orchestrare.

## Conseguenze

- Isolare la logica da Camel la rende testabile senza infrastruttura e
  Camel sostituibile.
- **Trappola trovata sul campo**: l'error handler era
  `deadLetterChannel("kafka:saga.dlq")`. Camel avvolge con l'error
  handler **ogni singolo processore di ogni rotta**, quindi all'avvio
  nascevano una quarantina di producer Kafka verso la DLQ, tutti bloccati
  in `INIT_PRODUCER_ID`: il broker smetteva di rispondere. La DLQ ora
  punta a un endpoint interno (`direct:saga-dlq`) e **una sola** rotta
  inoltra a Kafka. I test sono passati da 500 secondi a 51.
