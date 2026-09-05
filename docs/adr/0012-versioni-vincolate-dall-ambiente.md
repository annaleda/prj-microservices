# 0012 — Versioni di Java e Node vincolate dalla macchina

**Stato**: accettata
**Data**: 2026-08-28

## Contesto

Sulla macchina di sviluppo sono installati **Java 11** e **Node.js
16.20**. Spring Boot 3 richiede Java 17+; Angular 17 e Vite 5 richiedono
Node 18/20+.

## Decisione

**Lavorare con l'ambiente disponibile** invece di aggiornarlo:

| Componente | Versione | Vincolo |
|---|---|---|
| Spring Boot | 2.7.18 | Java 11 |
| Apache Camel | 3.22 | Camel 4 richiede Boot 3 |
| spring-kafka | 2.8.11 | trascinata da Boot 2.7 |
| Angular | 16 | Node 16 |
| Vite | 4 | Node 16 |
| httpclient (test) | 4 | la 5 richiede Spring 6 |

## Conseguenze

- Ogni scelta di versione a valle è **determinata** da questa: sono
  vincoli a catena, non preferenze.
- `KafkaTemplate.send()` restituisce un `ListenableFuture` e non un
  `CompletableFuture` (che arriva con spring-kafka 3.0): serve
  `.completable()` per convertirlo.
- Niente **virtual thread** (Java 21) né *structured concurrency*.
- Niente **componenti standalone** di default in Angular, né signals come
  modello primario.
- Le immagini Docker dei frontend usano **Node 20 in fase di build**,
  indipendentemente dal Node 16 dell'host: il vincolo è sull'ambiente di
  sviluppo, non sul risultato.
- Da rivedere quando la macchina verrà aggiornata: l'aggiornamento non è
  difficile, ma toccherebbe tutti i servizi insieme.

## Nota collaterale

Stessa logica per due problemi di rete: il Maven e l'npm della macchina
puntano a **repository interni aziendali** raggiungibili solo in VPN.
Invece di modificare la configurazione condivisa della macchina, ogni
progetto ha il proprio `.mvn/settings.xml` o `.npmrc` che punta ai
registry pubblici — configurazione **locale al progetto**, che non
interferisce con il resto.
