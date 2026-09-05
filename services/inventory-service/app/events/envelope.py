"""Envelope comune a tutti gli eventi Kafka (documento di design, sezione 8).

I servizi Java hanno una classe `EventEnvelope` equivalente: il contratto
condiviso e' il JSON sul topic, non una libreria comune.
"""
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

SOURCE = "inventory-service"

# Topic dell'event catalog usati da questo servizio.
TOPIC_ORDER_CREATED = "order.created"
TOPIC_ORDER_CANCELLED = "order.cancelled"
TOPIC_INVENTORY_RESERVED = "inventory.reserved"
TOPIC_INVENTORY_REJECTED = "inventory.rejected"
TOPIC_INVENTORY_RELEASED = "inventory.released"
TOPIC_SAGA_DLQ = "saga.dlq"


def build_envelope(event_type: str, data: Dict[str, Any], correlation_id: Optional[str] = None) -> Dict[str, Any]:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "eventVersion": 1,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "correlationId": correlation_id or str(uuid.uuid4()),
        "source": SOURCE,
        "data": data,
    }
