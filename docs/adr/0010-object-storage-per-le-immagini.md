# 0010 — Object storage per le immagini dei prodotti

**Stato**: accettata
**Data**: 2026-09-05

## Contesto

Il catalogo memorizzava solo un **indirizzo** dell'immagine, con una
scelta dichiarata: "gestire i binari è un problema a sé, da affrontare
quando servirà davvero". È servito: serviva poter **caricare un file**
dalla console di amministrazione.

## Decisione

Un **object storage S3-compatibile** — MinIO in locale — con l'**SDK
S3** e non quello proprietario di MinIO: il protocollo è lo stesso, e
passare a S3 significa cambiare endpoint e credenziali, non riscrivere
codice.

Nel database resta **solo il riferimento**, mai i byte.

## Alternative considerate

- **Binari nel database** (`bytea`) — nessuna infrastruttura nuova e
  pronto subito, ma un file per riga appesantisce ogni backup e ogni
  lettura della tabella prodotti, e un'immagine non ha nulla di
  transazionale: non deve stare nella stessa transazione del prezzo.
- **Bucket pubblico** con `imageUrl` che punta direttamente a MinIO — la
  strada facile, **scartata**: quell'URL è assoluto, è diverso in locale
  e dentro un cluster, e resterebbe **congelato in ogni riga** scritta
  prima del cambiamento. È lo stesso inciampo dell'issuer di Keycloak
  ([0005](0005-use-keycloak.md)), qui evitato per costruzione.

## Conseguenze

- Nel database va un percorso **relativo** (`/api/products/{id}/image`) e
  le immagini le serve il Catalog Service, che del catalogo è già
  proprietario e ne conosce le regole di accesso (lettura pubblica, come
  la vetrina). **Il browser non parla mai con MinIO.**
- Prezzo di questa scelta: i byte passano dal servizio invece di arrivare
  dallo storage. Con poche immagini e senza CDN non si nota; il passo
  successivo sarebbe rispondere con un redirect a un URL prefirmato.
- Il bucket lo crea `minio-init`, **non il servizio**: creare bucket è un
  permesso che a un'applicazione, su uno storage vero, non si concede.
- L'ordine delle due scritture è voluto: **prima il file, poi il
  riferimento**. Se il caricamento fallisce il prodotto resta con
  l'immagine di prima; il caso opposto lascia un oggetto orfano nel
  bucket, che è spazio sprecato e non un dato sbagliato.
- Nessun ridimensionamento: il file viene servito com'è caricato.
  Generare miniature è il passo successivo naturale.
