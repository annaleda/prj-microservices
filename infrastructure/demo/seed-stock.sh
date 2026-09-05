#!/bin/sh
# Rifornisce il magazzino con i prodotti del catalogo.
#
# Un prodotto nuovo nasce a zero pezzi: il Catalog Service pubblica
# `product.created`, l'Inventory Service crea la riga corrispondente, ma le
# unita' disponibili le dichiara il magazzino. Per i prodotti dimostrativi,
# che nessun magazziniere inserira' mai a mano, ci pensa questo script.
#
# Legge gli identificativi dal catalogo invece di conoscerli: e' il motivo
# per cui esiste. Una lista di id scritta a mano da qualche altra parte si
# disallinea appena il catalogo cambia, ed e' cosi' che il 5 settembre 2026
# ogni ordine sui prodotti nuovi finiva annullato.
#
# Uso:
#   sh infrastructure/demo/seed-stock.sh [quantita]
#
# Richiede: Keycloak, Catalog Service e Inventory Service avviati.
set -e

QUANTITY="${1:-50}"

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8180}"
REALM="${KEYCLOAK_REALM:-polyglot-commerce}"
CATALOG="${CATALOG_URL:-http://localhost:8081}"
INVENTORY="${INVENTORY_URL:-http://localhost:8083}"

# L'utente demo con il ruolo WAREHOUSE: le scorte le muove il magazzino.
USERNAME="${WAREHOUSE_USER:-warehouse}"
PASSWORD="${WAREHOUSE_PASSWORD:-warehouse}"

echo "Richiedo un token per '$USERNAME'..."
TOKEN=$(curl -s -X POST "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
  -d "client_id=admin-web" \
  -d "username=$USERNAME" \
  -d "password=$PASSWORD" \
  -d "grant_type=password" \
  | python -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo "Leggo il catalogo da $CATALOG/api/products..."
IDS=$(curl -s "$CATALOG/api/products" \
  | python -c "import sys,json; print(' '.join(str(p['id']) for p in json.load(sys.stdin)))")

if [ -z "$IDS" ]; then
  echo "Il catalogo e' vuoto: niente da rifornire."
  exit 0
fi

for id in $IDS; do
  printf 'Prodotto %-4s -> %s pezzi: ' "$id" "$QUANTITY"
  curl -s -o /dev/null -w '%{http_code}\n' \
    -X PUT "$INVENTORY/api/inventory/$id" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"quantityAvailable\": $QUANTITY}"
done

echo "Fatto."
