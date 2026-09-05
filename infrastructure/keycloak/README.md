# Keycloak (identity provider)

Keycloak e' l'identity provider del progetto (documento di design,
sezione 9). Gira in `docker-compose.yml` e usa **`auth-db`** come proprio
database, il container Postgres previsto fin dall'inizio per l'area
autenticazione e rimasto inutilizzato fino a questa fase.

```bash
docker compose up -d keycloak
```

- Console di amministrazione: `http://localhost:8180` (utente `admin`,
  password `admin` — vedi `.env`). E' l'amministratore del realm
  `master`, non un utente dell'applicazione.
- Realm dell'applicazione: **`polyglot-commerce`**.

## Il realm sta nel repository, non nella console

`realm-polyglot-commerce.json` viene importato all'avvio del container
(`start-dev --import-realm`). Ruoli, client e utenti demo sono quindi
versionati e ricreabili da zero: cancellando il volume di `auth-db` e
riavviando si torna esattamente alla stessa configurazione, senza dover
ripetere una sequenza di clic.

Per modificarlo: si cambia il JSON e si ricrea il container
(`docker compose up -d --force-recreate keycloak`). Le modifiche fatte
a mano dalla console **non** finiscono nel file e si perdono.

## Cosa contiene

**Ruoli di realm** (documento di design, sezione 9):

| Ruolo | Puo' fare |
|---|---|
| `CUSTOMER` | sfogliare il catalogo, creare i propri ordini e rileggerli |
| `ADMIN` | gestire il catalogo, vedere tutti gli ordini, gestire i pagamenti |
| `WAREHOUSE` | gestire scorte e prenotazioni |
| `SUPPORT` | leggere ordini e pagamenti di tutti, senza poterli modificare |

**Client** — entrambi pubblici con PKCE, perche' girano nel browser e
non possono custodire un segreto:

| Client | Applicazione | Redirect |
|---|---|---|
| `customer-web` | Customer Web (Angular) | `http://localhost:4200/*` |
| `admin-web` | Admin Web (React) | `http://localhost:5173/*` |

**Utenti demo** (password uguale allo username, valori da ambiente di
sviluppo):

| Utente | Ruolo |
|---|---|
| `customer` | CUSTOMER |
| `admin` | ADMIN |
| `warehouse` | WAREHOUSE |

## Ottenere un token dalla riga di comando

I due client hanno abilitato anche il *direct access grant*, che serve
a provare le API con `curl` senza passare dal browser (in produzione si
disabilita: e' il flusso che richiede username e password in chiaro al
client):

```bash
curl -s -X POST \
  http://localhost:8180/realms/polyglot-commerce/protocol/openid-connect/token \
  -d client_id=customer-web -d grant_type=password \
  -d username=customer -d password=customer | jq -r .access_token
```

## Comunicazione fra servizi

Il documento di design prevede anche l'**OAuth2 Client Credentials
Grant** per le chiamate sincrone fra microservizi. Non e' configurato:
oggi i servizi non si chiamano mai via HTTP fra loro, comunicano solo
per eventi Kafka, e Kafka non passa dai filtri di sicurezza HTTP.
Il client di servizio andra' aggiunto quando esistera' la prima chiamata
sincrona da proteggere, per non lasciare in giro credenziali inutilizzate.
