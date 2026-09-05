"""Test delle API REST. I container Postgres/Kafka e la fixture `client`
stanno in conftest.py, condivisi con i test della saga.
"""


def test_get_inventory_seeded(client, login):
    login("CUSTOMER")

    response = client.get("/api/inventory/1")
    assert response.status_code == 200
    body = response.json()
    assert body["productId"] == 1
    assert body["quantityAvailable"] == 100
    assert body["quantityReserved"] == 0


def test_get_inventory_not_found(client, login):
    login("CUSTOMER")

    response = client.get("/api/inventory/999")
    assert response.status_code == 404


def test_reservation_lifecycle(client, login):
    login("WAREHOUSE")

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


def test_insufficient_stock_is_rejected(client, login):
    login("WAREHOUSE")

    response = client.post("/api/inventory/reservations", json={"productId": 3, "quantity": 9999})
    assert response.status_code == 409


def test_release_unknown_reservation_returns_404(client, login):
    login("WAREHOUSE")

    response = client.delete("/api/inventory/reservations/999999")
    assert response.status_code == 404


def test_inventory_requires_a_token(client):
    # Nessun login: la dipendenza vera respinge la richiesta senza nemmeno
    # contattare Keycloak, perche' manca del tutto il bearer token.
    assert client.get("/api/inventory/1").status_code == 401
    assert client.post("/api/inventory/reservations", json={"productId": 1, "quantity": 1}).status_code == 401


def test_reservations_are_reserved_to_the_warehouse(client, login):
    login("CUSTOMER")

    response = client.post("/api/inventory/reservations", json={"productId": 1, "quantity": 1})

    assert response.status_code == 403


def test_stock_can_be_declared_by_the_warehouse(client, login):
    """Rifornimento: e' l'unico modo di aumentare le scorte.

    La saga sa solo riservare e rilasciare, e un prodotto appena creato
    nasce a zero disponibili: senza questo endpoint le scorte si
    cambierebbero solo scrivendo nel database a mano.
    """
    login("WAREHOUSE")

    response = client.put("/api/inventory/920", json={"quantityAvailable": 30})

    assert response.status_code == 200
    assert response.json()["quantityAvailable"] == 30
    assert client.get("/api/inventory/920").json()["quantityAvailable"] == 30


def test_declaring_stock_leaves_reserved_units_alone(client, login):
    """Le unita' gia' impegnate da un ordine in corso non si toccano: un
    conteggio di magazzino riguarda cio' che c'e' sullo scaffale, non cio'
    che e' stato promesso a qualcun altro."""
    login("WAREHOUSE")
    client.put("/api/inventory/921", json={"quantityAvailable": 10})

    reservation = client.post("/api/inventory/reservations", json={"productId": 921, "quantity": 4})
    assert reservation.status_code == 201

    client.put("/api/inventory/921", json={"quantityAvailable": 50})

    stock = client.get("/api/inventory/921").json()
    assert stock["quantityAvailable"] == 50
    assert stock["quantityReserved"] == 4


def test_declaring_stock_is_reserved_to_the_warehouse(client, login):
    login("CUSTOMER")

    assert client.put("/api/inventory/1", json={"quantityAvailable": 999}).status_code == 403


def test_declaring_stock_requires_a_token(client):
    assert client.put("/api/inventory/1", json={"quantityAvailable": 999}).status_code == 401


def test_health_stays_public(client):
    assert client.get("/health").status_code == 200
