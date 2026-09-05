"""Test della parte a eventi: il servizio consuma order.created /
order.cancelled e pubblica l'esito della riserva.

Usano prodotti dedicati (900+) invece di quelli seedati all'avvio, cosi'
da non dipendere dall'ordine di esecuzione rispetto ai test REST.
"""
import time

import pytest

from tests.conftest import await_event, await_events, publish

STOCK = 100


@pytest.fixture(scope="module", autouse=True)
def saga_products(client):
    from app.database import SessionLocal
    from app.models import InventoryItem

    with SessionLocal() as db:
        for product_id in (900, 901, 902, 903):
            if db.get(InventoryItem, product_id) is None:
                db.add(InventoryItem(product_id=product_id, quantity_available=STOCK, quantity_reserved=0))
        db.commit()


def order_created(bootstrap, order_id, product_id, quantity):
    publish(bootstrap, "order.created", order_id, "ORDER_CREATED", {
        "orderId": order_id,
        "customerEmail": "customer@example.com",
        "totalAmount": 59.80,
        "items": [{"productId": product_id, "quantity": quantity, "unitPrice": 29.90}],
    })


def test_order_created_reserves_stock(client, login, kafka_bootstrap):
    login("WAREHOUSE")

    order_created(kafka_bootstrap, 300, 900, 3)

    envelope = await_event(kafka_bootstrap, "inventory.reserved", 300)

    assert envelope["eventType"] == "INVENTORY_RESERVED"
    assert envelope["source"] == "inventory-service"
    # Il correlationId dell'ordine viene propagato all'evento di risposta.
    assert envelope["correlationId"] == "test-correlation"

    reservations = envelope["data"]["reservations"]
    assert len(reservations) == 1
    assert reservations[0]["productId"] == 900
    assert reservations[0]["quantity"] == 3

    stock = client.get("/api/inventory/900").json()
    assert stock["quantityAvailable"] == STOCK - 3
    assert stock["quantityReserved"] == 3


def test_order_created_without_enough_stock_is_rejected(client, login, kafka_bootstrap):
    login("WAREHOUSE")

    order_created(kafka_bootstrap, 301, 901, 9999)

    envelope = await_event(kafka_bootstrap, "inventory.rejected", 301)

    assert envelope["eventType"] == "INVENTORY_REJECTED"
    assert "Insufficient stock" in envelope["data"]["reason"]

    # Nessuna scorta impegnata: la riserva e' tutto-o-niente.
    stock = client.get("/api/inventory/901").json()
    assert stock["quantityAvailable"] == STOCK
    assert stock["quantityReserved"] == 0


def test_order_cancelled_releases_the_reservation(client, login, kafka_bootstrap):
    login("WAREHOUSE")

    order_created(kafka_bootstrap, 302, 902, 4)
    await_event(kafka_bootstrap, "inventory.reserved", 302)

    # Compensazione della saga: l'ordine viene annullato (pagamento fallito o
    # scorte rifiutate altrove) e le scorte tornano disponibili.
    publish(kafka_bootstrap, "order.cancelled", 302, "ORDER_CANCELLED",
            {"orderId": 302, "reason": "Payment failed"})

    envelope = await_event(kafka_bootstrap, "inventory.released", 302)
    assert envelope["data"]["reservations"][0]["quantity"] == 4

    stock = client.get("/api/inventory/902").json()
    assert stock["quantityAvailable"] == STOCK
    assert stock["quantityReserved"] == 0


def test_redelivered_order_created_does_not_reserve_twice(client, login, kafka_bootstrap):
    login("WAREHOUSE")

    order_created(kafka_bootstrap, 303, 903, 5)
    await_event(kafka_bootstrap, "inventory.reserved", 303)

    # Stesso evento consegnato una seconda volta (at-least-once): le scorte
    # non devono essere impegnate di nuovo. Si attende il secondo
    # inventory.reserved prima di guardare le scorte, altrimenti il
    # controllo potrebbe precedere la gestione della ri-consegna.
    order_created(kafka_bootstrap, 303, 903, 5)
    outcomes = await_events(kafka_bootstrap, "inventory.reserved", 303, count=2)
    assert outcomes[1]["data"]["reservations"] == outcomes[0]["data"]["reservations"]

    stock = client.get("/api/inventory/903").json()
    assert stock["quantityAvailable"] == STOCK - 5
    assert stock["quantityReserved"] == 5


def test_product_created_starts_tracking_the_product(client, login, kafka_bootstrap):
    """Un prodotto nuovo ottiene la sua riga di magazzino, a zero pezzi.

    E' il rimedio al bug del 5 settembre 2026: senza riga, l'Inventory
    Service non riconosce il prodotto e ogni ordine che lo contiene viene
    rifiutato, facendo annullare l'ordine dalla saga senza che il cliente
    capisca perche'.
    """
    login("WAREHOUSE")
    assert client.get("/api/inventory/910").status_code == 404

    publish(kafka_bootstrap, "product.created", 910, "PRODUCT_CREATED", {
        "productId": 910,
        "sku": "SKU-NEW-01",
        "name": "Prodotto nuovo",
    })

    stock = _await_inventory(client, 910)
    assert stock["quantityAvailable"] == 0
    assert stock["quantityReserved"] == 0


def test_product_created_does_not_reset_existing_stock(client, login, kafka_bootstrap):
    """Kafka puo' riconsegnare lo stesso evento: ricreare la riga
    azzererebbe scorte gia' dichiarate dal magazzino."""
    login("WAREHOUSE")

    publish(kafka_bootstrap, "product.created", 911, "PRODUCT_CREATED", {
        "productId": 911, "sku": "SKU-NEW-02", "name": "Prodotto rifornito",
    })
    _await_inventory(client, 911)

    assert client.put("/api/inventory/911", json={"quantityAvailable": 42}).status_code == 200

    publish(kafka_bootstrap, "product.created", 911, "PRODUCT_CREATED", {
        "productId": 911, "sku": "SKU-NEW-02", "name": "Prodotto rifornito",
    })

    # L'evento non porta scorte: se anche fosse riconsegnato piu' volte, le
    # 42 unita' devono restare. Si attende un momento perche' il consumer e'
    # asincrono e qui il segnale atteso e' un non-cambiamento.
    time.sleep(3)
    assert client.get("/api/inventory/911").json()["quantityAvailable"] == 42


def _await_inventory(client, product_id, timeout=30.0):
    """Attende che il consumer abbia creato la riga di magazzino."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        response = client.get(f"/api/inventory/{product_id}")
        if response.status_code == 200:
            return response.json()
        time.sleep(0.5)
    raise AssertionError(f"No inventory row for product {product_id} within {timeout}s")
