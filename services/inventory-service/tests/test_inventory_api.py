"""Test delle API REST. I container Postgres/Kafka e la fixture `client`
stanno in conftest.py, condivisi con i test della saga.
"""


def test_get_inventory_seeded(client):
    response = client.get("/api/inventory/1")
    assert response.status_code == 200
    body = response.json()
    assert body["productId"] == 1
    assert body["quantityAvailable"] == 100
    assert body["quantityReserved"] == 0


def test_get_inventory_not_found(client):
    response = client.get("/api/inventory/999")
    assert response.status_code == 404


def test_reservation_lifecycle(client):
    create_response = client.post("/api/inventory/reservations", json={"productId": 2, "quantity": 10})
    assert create_response.status_code == 201
    reservation = create_response.json()
    assert reservation["quantity"] == 10

    after_reserve = client.get("/api/inventory/2").json()
    assert after_reserve["quantityAvailable"] == 40
    assert after_reserve["quantityReserved"] == 10

    delete_response = client.delete(f"/api/inventory/reservations/{reservation['id']}")
    assert delete_response.status_code == 204

    after_release = client.get("/api/inventory/2").json()
    assert after_release["quantityAvailable"] == 50
    assert after_release["quantityReserved"] == 0


def test_insufficient_stock_is_rejected(client):
    response = client.post("/api/inventory/reservations", json={"productId": 3, "quantity": 9999})
    assert response.status_code == 409


def test_release_unknown_reservation_returns_404(client):
    response = client.delete("/api/inventory/reservations/999999")
    assert response.status_code == 404
