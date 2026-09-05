"""Consumer Kafka: la parte di questo servizio che partecipa alla saga.

Gira su un thread dedicato invece che sul loop asyncio di FastAPI, perche'
l'accesso al database (SQLAlchemy) e' sincrono e bloccherebbe il loop.
"""
import json
import logging
import threading
from typing import Any, Dict

from confluent_kafka import Consumer

from app.database import SessionLocal
from app.events.envelope import (
    TOPIC_INVENTORY_REJECTED,
    TOPIC_INVENTORY_RELEASED,
    TOPIC_INVENTORY_RESERVED,
    TOPIC_ORDER_CANCELLED,
    TOPIC_ORDER_CREATED,
    TOPIC_SAGA_DLQ,
    build_envelope,
)
from app.events.producer import bootstrap_servers, publish
from app.services.inventory import InsufficientStock, UnknownProduct, release_order, reserve_order

logger = logging.getLogger(__name__)

CONSUMER_GROUP = "inventory-service"


class SagaConsumer(threading.Thread):

    def __init__(self) -> None:
        super().__init__(name="inventory-saga-consumer", daemon=True)
        self._stopping = threading.Event()
        self._consumer = Consumer({
            "bootstrap.servers": bootstrap_servers(),
            "group.id": CONSUMER_GROUP,
            "auto.offset.reset": "earliest",
            # Commit manuale dopo la gestione del messaggio: at-least-once,
            # che gli handler qui sotto rendono innocuo essendo idempotenti.
            "enable.auto.commit": False,
        })

    def run(self) -> None:
        self._consumer.subscribe([TOPIC_ORDER_CREATED, TOPIC_ORDER_CANCELLED])
        logger.info("Kafka consumer started (group=%s, brokers=%s)", CONSUMER_GROUP, bootstrap_servers())

        try:
            while not self._stopping.is_set():
                message = self._consumer.poll(1.0)
                if message is None:
                    continue
                if message.error():
                    logger.error("Kafka error: %s", message.error())
                    continue

                try:
                    self._handle(message.topic(), json.loads(message.value()))
                except Exception:
                    # Il messaggio non e' processabile: finisce in DLQ e
                    # l'offset viene comunque avanzato, altrimenti bloccherebbe
                    # la partizione riproponendosi all'infinito.
                    logger.exception("Unable to handle message from %s, sending to DLQ", message.topic())
                    self._to_dlq(message)

                self._consumer.commit(message=message, asynchronous=False)
        finally:
            self._consumer.close()
            logger.info("Kafka consumer stopped")

    def stop(self) -> None:
        self._stopping.set()

    def _handle(self, topic: str, envelope: Dict[str, Any]) -> None:
        data = envelope.get("data", {})
        correlation_id = envelope.get("correlationId")

        if topic == TOPIC_ORDER_CREATED:
            self._on_order_created(data, correlation_id)
        elif topic == TOPIC_ORDER_CANCELLED:
            self._on_order_cancelled(data, correlation_id)

    def _on_order_created(self, data: Dict[str, Any], correlation_id: str) -> None:
        order_id = int(data["orderId"])
        items = data.get("items", [])
        logger.info("Reserving stock for order %s (%d item types)", order_id, len(items))

        with SessionLocal() as db:
            try:
                reservations = reserve_order(db, order_id, items)
            except (UnknownProduct, InsufficientStock) as error:
                logger.info("Reservation rejected for order %s: %s", order_id, error)
                publish(
                    TOPIC_INVENTORY_REJECTED,
                    order_id,
                    build_envelope(
                        "INVENTORY_REJECTED",
                        {"orderId": order_id, "reason": str(error)},
                        correlation_id,
                    ),
                )
                return

        publish(
            TOPIC_INVENTORY_RESERVED,
            order_id,
            build_envelope(
                "INVENTORY_RESERVED",
                {"orderId": order_id, "reservations": reservations},
                correlation_id,
            ),
        )

    def _on_order_cancelled(self, data: Dict[str, Any], correlation_id: str) -> None:
        order_id = int(data["orderId"])

        with SessionLocal() as db:
            released = release_order(db, order_id)

        if not released:
            logger.info("Order %s cancelled: no reservation to release", order_id)
            return

        logger.info("Order %s cancelled: released %d reservation(s)", order_id, len(released))
        publish(
            TOPIC_INVENTORY_RELEASED,
            order_id,
            build_envelope(
                "INVENTORY_RELEASED",
                {"orderId": order_id, "reservations": released},
                correlation_id,
            ),
        )

    def _to_dlq(self, message) -> None:
        try:
            payload = message.value().decode("utf-8", errors="replace")
        except Exception:  # pragma: no cover - difensivo
            payload = None
        publish(
            TOPIC_SAGA_DLQ,
            message.key().decode() if message.key() else "unknown",
            build_envelope(
                "SAGA_DLQ",
                {"originalTopic": message.topic(), "payload": payload},
            ),
        )
