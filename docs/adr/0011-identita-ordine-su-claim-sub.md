# 0011 — La proprietà di un ordine si basa sul claim `sub`

**Stato**: accettata
**Data**: 2026-09-05

## Contesto

Prima dell'autenticazione il checkout chiedeva l'email e l'ordine veniva
intestato a quella. Quando è arrivato Keycloak
([0005](0005-use-keycloak.md)), il filtro "vedo solo i miei ordini" è
rimasto **sull'email**.

Il difetto è emerso da una segnalazione reale: *"mi sono registrata e ho
due ordini che non ho mai fatto"*. Nel database c'erano esattamente due
ordini con quell'indirizzo, creati quando il checkout aveva ancora il
campo email libero.

**L'email non è una prova di identità**: si può cambiare, e in fase di
registrazione chiunque può dichiarare quella di un altro — tanto più che
Keycloak, così configurato, non la verifica. Bastava registrarsi con
l'indirizzo di una persona per vederne gli ordini.

## Decisione

La proprietà si basa sul claim **`sub`** del token, l'identificativo
stabile assegnato dall'identity provider. Aggiunta la colonna
`orders.customer_id`.

`customerEmail` resta, ma **solo per mostrarla e per le notifiche, mai
per autorizzare**.

## Conseguenze

- Gli ordini creati prima dell'autenticazione hanno `customer_id` nullo:
  non sono attribuibili a nessun account e restano visibili al solo
  personale interno.
- Chiedere l'ordine di un altro restituisce **403 e non 404**: l'ordine
  esiste, semplicemente non è tuo. Un 404 direbbe una bugia.
- Il filtro sta nel **service**, non nella configurazione di sicurezza:
  è una regola sui **dati**, non sull'URL.
- Test di regressione che inchioda il comportamento: **due account con la
  stessa email** e identità diverse non si vedono gli ordini a vicenda.

## Lezione

Una scelta ragionevole quando è stata presa — intestare l'ordine
all'email digitata — diventa una falla nel momento in cui accanto nasce
un sistema di identità. Il codice in questione non era stato toccato da
nessuno.
