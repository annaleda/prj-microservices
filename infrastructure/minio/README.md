# MinIO (object storage, locale via Docker Compose)

Storage S3-compatibile dove finiscono le **immagini dei prodotti**
caricate da Admin Web. Avviato insieme al resto dell'infrastruttura:

```bash
docker compose up -d minio minio-init
```

- `minio`: il server. API S3 su `http://localhost:9000` (dagli altri
  container: `http://minio:9000`), console web su
  `http://localhost:9001` (utente e password: `minioadmin`, da `.env`).
- `minio-init`: container "one-shot" che crea il bucket
  `product-images`, come `kafka-init` fa con i topic. Idempotente.

## Perche' un object storage e non il database

Il Catalog Service conserva nel proprio database solo il **riferimento**
all'immagine, non il file. Un binario da qualche centinaio di kilobyte
per riga appesantirebbe ogni backup e ogni lettura della tabella
prodotti, e un'immagine non ha nulla di transazionale: non deve stare
nella stessa transazione del prezzo.

Si usa l'SDK S3 e non quello specifico di MinIO: il protocollo e' lo
stesso, e passare a S3 (o a qualunque altro storage compatibile)
richiede di cambiare endpoint e credenziali, non di riscrivere il
codice.

## Come viene servita un'immagine

```
Admin Web --POST /api/products/{id}/image--> Catalog Service --PUT--> MinIO
                                                   |
browser  <--GET /api/products/{id}/image-----------+
```

Il browser **non parla mai con MinIO**. Il bucket resta privato e le
immagini le serve il Catalog Service, che del catalogo e' gia' il
proprietario e ne conosce le regole di accesso (la lettura e' pubblica,
come il resto della vetrina).

Questo evita anche un problema concreto: con un bucket pubblico, nel
database finirebbe un **URL assoluto** di MinIO — che e' diverso in
locale e dentro un cluster, e resterebbe congelato in ogni riga scritta
prima del cambiamento. E' lo stesso inciampo dell'issuer di Keycloak
(vedi l'avvertenza nell'overlay `local` dei manifest Kubernetes), qui
evitato per costruzione: nel database c'e' un percorso relativo
(`/api/products/{id}/image`), che funziona sia col proxy di sviluppo
sia dietro l'API Gateway.

Il prezzo di questa scelta e' che i byte passano dal servizio invece di
arrivare direttamente dallo storage: senza CDN e con poche immagini non
si nota, e il passo successivo sarebbe rispondere con un redirect a un
URL prefirmato invece che con il file.

## Limiti attuali

- **5 MB per immagine**, controllati sia dal framework
  (`spring.servlet.multipart`) sia nel servizio.
- Un'immagine per prodotto: la chiave e' `products/{id}`, quindi
  ricaricare sostituisce senza lasciare file orfani.
- Nessun ridimensionamento: il file viene servito come e' stato
  caricato. Generare miniature e' il naturale passo successivo.
- Il bucket lo crea `minio-init`, non il servizio: creare bucket e' un
  permesso che a un'applicazione, su uno storage vero, non si concede.
