# 0005 — Keycloak come identity provider

**Stato**: accettata
**Data**: 2026-09-05

## Contesto

Fino alla Phase 4 le API erano aperte: chiunque poteva creare ordini
intestandoli a un'email scritta a mano, e la console di amministrazione
faceva CRUD sul catalogo senza autenticarsi.

Serviva un'identità vera, con ruoli, per due frontend che girano nel
browser.

## Decisione

**Keycloak**, con il realm **importato da JSON**
(`infrastructure/keycloak/realm-polyglot-commerce.json`) e non
configurato dalla console: ruoli, client e utenti demo sono versionati e
ricreabili da zero.

I servizi sono **resource server**: verificano la firma dei token con le
chiavi pubbliche del realm (JWKS). Nessun segreto è condiviso con
Keycloak.

Due client **pubblici con PKCE** — girano nel browser e non possono
custodire un segreto.

## Alternative considerate

- **Un Auth Service scritto a mano** — significa implementare da soli
  hashing delle password, rotazione delle chiavi, recupero password,
  protezione dai tentativi ripetuti. Tutte cose che si sbagliano.
- **Un modulo di login dentro l'applicazione Angular** che manda username
  e password a Keycloak (*Resource Owner Password Credentials*)
  — **scartato**: l'applicazione vedrebbe le password in chiaro, si
  perderebbero SSO, MFA e recupero password, e il grant è sconsigliato in
  OAuth 2.1. Al suo posto un **tema di Keycloak** con la grafica del
  negozio: stesso risultato visivo, le password non passano mai
  dall'applicazione.

## Conseguenze

- Il tema eredita da quello standard e **sovrascrive solo il CSS**: i
  template FreeMarker restano gli originali, così un aggiornamento di
  Keycloak non obbliga a riallineare pagine non nostre.
- La registrazione la fornisce Keycloak, già con validazione e controllo
  dei duplicati. Ha richiesto di aggiungere `CUSTOMER` ai ruoli di
  default del realm: senza, un nuovo iscritto prendeva 403 al primo
  ordine.
- `auth-db`, previsto per l'Auth Service, oggi è il database di Keycloak.
- **Il valore dell'issuer è delicato**: non serve solo a *raggiungere*
  Keycloak, decide anche quale `iss` è accettabile nel token. Se il
  browser ottiene i token da un indirizzo e il servizio se ne aspetta un
  altro, li rifiuta tutti pur essendo la firma valida.
