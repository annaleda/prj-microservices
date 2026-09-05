"""Infrastruttura condivisa dai test: un Postgres e un Kafka reali
(Testcontainers), avviati una volta per sessione.

Sono qui e non nei singoli moduli perche' `app.database` costruisce
l'engine SQLAlchemy al momento dell'import, leggendo le variabili
d'ambiente: se ogni file di test avesse i propri container, il secondo
import (gia' in cache) continuerebbe a puntare al database del primo.
"""
import json
import os
import time
import uuid

import pytest
from confluent_kafka import Consumer, Producer
from confluent_kafka.admin import AdminClient, NewTopic
from testcontainers.kafka import KafkaContainer
from testcontainers.postgres import PostgresContainer

# Gli stessi topic che init-topics.sh crea nello stack Docker.
TOPICS = [
    "product.created",
    "order.created",
    "order.cancelled",
    "inventory.reserved",
    "inventory.rejected",
    "inventory.released",
    "saga.dlq",
]


@pytest.fixture(scope="session")
def postgres_container():
    with PostgresContainer(
        "postgres:16-alpine", dbname="inventory", username="inventory", password="inventory"
    ) as postgres:
        yield postgres


@pytest.fixture(scope="session")
def kafka_bootstrap():
    with KafkaContainer(image="confluentinc/cp-kafka:7.4.0") as kafka:
        bootstrap = kafka.get_bootstrap_server()
        # I topic vengono creati prima di far partire il servizio, come nello
        # stack reale: un consumer che si iscrive a un topic inesistente ci
        # mette molto di piu' ad accorgersi che nel frattempo e' nato.
        admin = AdminClient({"bootstrap.servers": bootstrap})
        for future in admin.create_topics(
            [NewTopic(topic, num_partitions=1, replication_factor=1) for topic in TOPICS]
        ).values():
            future.result()
        yield bootstrap


@pytest.fixture(scope="session")
def client(postgres_container, kafka_bootstrap):
    os.environ["INVENTORY_DB_HOST"] = postgres_container.get_container_host_ip()
    os.environ["INVENTORY_DB_PORT"] = postgres_container.get_exposed_port(5432)
    os.environ["INVENTORY_DB_NAME"] = "inventory"
    os.environ["INVENTORY_DB_USER"] = "inventory"
    os.environ["INVENTORY_DB_PASSWORD"] = "inventory"
    os.environ["KAFKA_BOOTSTRAP_SERVERS"] = kafka_bootstrap

    # Import ritardato: app.database legge le variabili d'ambiente al
    # momento dell'import per costruire l'engine SQLAlchemy, quindi deve
    # avvenire dopo aver puntato le env var ai container di test.
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def login(client):
    """Fa risultare le richieste come provenienti da un utente con dati ruoli.

    Sostituisce la sola dipendenza che valida il token: le regole di
    autorizzazione degli endpoint restano quelle vere, mentre non serve un
    Keycloak in piedi per ogni test. Che i token veri vengano accettati e'
    verificato end-to-end a mano (vedi README, sezione 3 "Autenticazione con Keycloak").
    """
    from app.main import app
    from app.security import Principal, current_principal

    def _login(*roles, username="test-user", email="test@example.com"):
        app.dependency_overrides[current_principal] = lambda: Principal(
            username=username, email=email, roles=list(roles)
        )

    yield _login
    app.dependency_overrides.pop(current_principal, None)


def publish(bootstrap: str, topic: str, key, event_type: str, data: dict) -> None:
    envelope = {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "eventVersion": 1,
        "timestamp": "2026-09-05T00:00:00Z",
        "correlationId": "test-correlation",
        "source": "test",
        "data": data,
    }
    producer = Producer({"bootstrap.servers": bootstrap})
    producer.produce(topic, key=str(key), value=json.dumps(envelope))
    producer.flush(10)


def await_events(bootstrap: str, topic: str, order_id: int, count: int = 1, timeout: float = 30.0) -> list:
    """Attende sul topic `count` eventi che riguardino l'ordine indicato.

    Ogni chiamata usa un consumer group nuovo e riparte dall'inizio del
    topic, quindi rivede anche gli eventi gia' letti in precedenza: per
    verificare che ne sia arrivato uno *in piu'* bisogna chiedere il totale
    atteso, non aspettarne un altro.
    """
    consumer = Consumer({
        "bootstrap.servers": bootstrap,
        "group.id": f"test-{uuid.uuid4()}",
        "auto.offset.reset": "earliest",
        "enable.auto.commit": False,
    })
    matching = []
    try:
        consumer.subscribe([topic])
        deadline = time.time() + timeout
        while time.time() < deadline and len(matching) < count:
            message = consumer.poll(1.0)
            if message is None or message.error():
                continue
            envelope = json.loads(message.value())
            if envelope.get("data", {}).get("orderId") == order_id:
                matching.append(envelope)
    finally:
        consumer.close()

    if len(matching) < count:
        raise AssertionError(
            f"Expected {count} event(s) for order {order_id} on topic {topic}, "
            f"got {len(matching)} within {timeout}s"
        )
    return matching


def await_event(bootstrap: str, topic: str, order_id: int, timeout: float = 30.0) -> dict:
    """Attende sul topic un evento che riguardi l'ordine indicato."""
    return await_events(bootstrap, topic, order_id, count=1, timeout=timeout)[0]
