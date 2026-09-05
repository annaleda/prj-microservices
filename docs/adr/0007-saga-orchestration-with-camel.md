# 0007 — Saga orchestrata, non coreografata

**Stato**: accettata
**Data**: 2026-09-05

## Contesto

Il flusso "crea l'ordine, riserva le scorte, addebita il pagamento"
attraversa tre servizi con **tre database distinti**
([0003](0003-database-per-service.md)). Non esiste una transazione ACID
che li copra.

Il documento di design era anche **in contraddizione con sé stesso**: la
sezione sulla saga diceva che l'Integration Service invoca l'Inventory
via REST, mentre la scheda dell'Inventory elencava `order.created` fra
gli eventi consumati.

## Decisione

Una **saga orchestrata** dall'Integration Service, e **interamente a
eventi**: l'Inventory consuma `order.created` come tutti gli altri.
Nessun servizio di dominio chiama un altro via HTTP.

Risolta così la contraddizione, coerentemente con il principio "Event
First".

## Alternative considerate

- **Transazione distribuita (2PC/XA)** — blocca risorse per tutta la
  durata, ha un coordinatore che è un single point of failure, e non è
  supportata da Kafka né dalla maggior parte dei database cloud.
- **Saga coreografata** — ogni servizio reagisce agli eventi altrui,
  senza coordinatore. Più disaccoppiata, ma con più di due o tre passi
  **il flusso non è scritto da nessuna parte**: è sparso in N servizi, e
  per capirlo bisogna leggerli tutti.

## Conseguenze

- Il flusso completo si legge in un file solo (`SagaOrchestrator`).
- **La compensazione non ha un evento dedicato**: `order.cancelled` *è*
  il segnale che fa rilasciare le scorte. Meno eventi, e un solo
  significato per "quest'ordine non si fa".
- La consistenza è **eventuale**: esiste una finestra in cui l'ordine è
  `CREATED` e le scorte sono già impegnate. Il frontend la gestisce
  esplicitamente rileggendo l'ordine finché non cambia stato.
- **Lo stato delle saghe è in memoria** — serve perché
  `inventory.reserved` non trasporta l'importo da pagare, che sta solo in
  `order.created`. Due conseguenze dichiarate: il Deployment gira a
  **una sola replica**, e al riavvio le saghe aperte perdono il contesto.
  In quel caso un `inventory.reserved` senza stato viene trattato come
  fallimento — ordine annullato, scorte rilasciate — invece di lasciare
  ordine e scorte bloccati: **fallire in modo pulito è meglio che restare
  appesi**.
- L'evoluzione naturale è uno store persistente (database, o un
  framework come Temporal).
- L'annullamento porta un **`reasonCode`** oltre al testo libero: il
  codice è il contratto su cui il checkout sceglie il messaggio, il testo
  serve ai log e può essere riformulato senza rompere il frontend.
