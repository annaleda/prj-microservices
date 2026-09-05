import json
import logging
import os
from typing import Any, Dict, Optional

from confluent_kafka import Producer

logger = logging.getLogger(__name__)

_producer: Optional[Producer] = None


def bootstrap_servers() -> str:
    return os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094")


def get_producer() -> Producer:
    global _producer
    if _producer is None:
        _producer = Producer({"bootstrap.servers": bootstrap_servers()})
    return _producer


def publish(topic: str, key: str, envelope: Dict[str, Any]) -> None:
    producer = get_producer()
    producer.produce(topic, key=str(key), value=json.dumps(envelope))
    # poll(0) consegna i callback di delivery gia' pronti senza bloccare;
    # il flush vero e proprio avviene alla chiusura del servizio.
    producer.poll(0)
    logger.info(
        "Published %s to topic %s (key=%s, correlationId=%s)",
        envelope["eventType"], topic, key, envelope["correlationId"],
    )


def shutdown() -> None:
    if _producer is not None:
        _producer.flush(5)
