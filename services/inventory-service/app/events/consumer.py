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
    TOPIC_PRODUCT_CREATED,
    TOPIC_SAGA_DLQ,
    build_envelope,
)
from app.events.producer import bootstrap_servers, publish
from app.services.inventory import (
    InsufficientStock,
    UnknownProduct,
    ensure_item,
    release_order,
    reserve_order,
)

logger = logging.getLogger(__name__)

CONSUMER_GROUP = "inventory-service"


try:
    from opentelemetry import trace
    from opentelemetry.propagate import extract
    from opentelemetry.trace import SpanKind

    _tracer = trace.get_tracer(__name__)
except ImportError:  # pragma: no cover - il tracing e' facoltativo
    _tracer = None


def _contesto_dal_messaggio(message):
    """Estrae il contesto di tracing dagli header del messaggio Kafka.

    **Perche' a mano.** Nei servizi Java il `-javaagent` di OpenTelemetry
    collega tutto da solo, producer e consumer compresi. In Python
    l'auto-strumentazione copre HTTP e SQLAlchemy, e la strumentazione di
    confluent-kafka crea sì uno span per il consumo, ma lo radica in una
    trace **nuova** invece di agganciarlo a quella del produttore: nel
    risultato l'Inventory Service compariva con trace proprie, scollegate
    dall'ordine che stava processando.

    Il contesto viaggia nell'header `traceparent` (standard W3C Trace
    Context), scritto dal produttore. `extract` lo trasforma nel contesto
    da usare come genitore: e' esattamente il meccanismo che gli agent
    automatizzano.
    """
    intestazioni = {}
    for chiave, valore in (message.headers() or []):
        if isinstance(valore, bytes):
            valore = valore.decode("utf-8", errors="replace")
        intestazioni[chiave] = valore
    return extract(intestazioni)


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
        self._consumer.subscribe([TOPIC_PRODUCT_CREATED, TOPIC_ORDER_CREATED, TOPIC_ORDER_CANCELLED])
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
                    self._handle_traced(message)
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

    def _handle_traced(self, message) -> None:
        """Gestisce il messaggio dentro uno span agganciato al produttore.

        Senza tracing installato (`_tracer is None`) chiama direttamente
        l'handler: l'osservabilita' e' facoltativa, il servizio no.
        """
        payload = json.loads(message.value())

        if _tracer is None:
            self._handle(message.topic(), payload)
            return

        with _tracer.start_as_current_span(
            f"{message.topic()} process",
            context=_contesto_dal_messaggio(message),
            kind=SpanKind.CONSUMER,
            attributes={
                "messaging.system": "kafka",
                "messaging.destination.name": message.topic(),
                "messaging.kafka.partition": message.partition(),
                "messaging.kafka.message.offset": message.offset(),
            },
        ):
            self._handle(message.topic(), payload)

    def _handle(self, topic: str, envelope: Dict[str, Any]) -> None:
        data = envelope.get("data", {})
        correlation_id = envelope.get("correlationId")

        if topic == TOPIC_PRODUCT_CREATED:
            self._on_product_created(data)
        elif topic == TOPIC_ORDER_CREATED:
            self._on_order_created(data, correlation_id)
        elif topic == TOPIC_ORDER_CANCELLED:
            self._on_order_cancelled(data, correlation_id)

    def _on_product_created(self, data: Dict[str, Any]) -> None:
        """Un prodotto nuovo nel catalogo ottiene subito la sua riga di
        magazzino, a zero disponibili.

        Non si pubblica nulla in risposta: nessuno attende una conferma, e
        `inventory.updated` e' l'evento delle variazioni di scorta, non
        della nascita di una riga vuota.
        """
        product_id = int(data["productId"])
        logger.info("Product %s created (sku=%s): tracking it in inventory", product_id, data.get("sku"))

        with SessionLocal() as db:
            ensure_item(db, product_id)

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
