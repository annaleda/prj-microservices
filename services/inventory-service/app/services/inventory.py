"""Logica di dominio delle scorte.

Sta qui e non nel router perche' ha due punti di ingresso: le API REST
(prenotazione singola, usata manualmente o dal back office) e il consumer
Kafka che partecipa alla saga (prenotazione di tutti gli item di un
ordine).
"""
import logging
from typing import Any, Dict, List, Sequence

from sqlalchemy.orm import Session

from app.models import InventoryItem, Reservation

logger = logging.getLogger(__name__)


class UnknownProduct(Exception):
    def __init__(self, product_id: int) -> None:
        super().__init__(f"Inventory not found for product {product_id}")
        self.product_id = product_id


class InsufficientStock(Exception):
    def __init__(self, product_id: int, requested: int, available: int) -> None:
        super().__init__(
            f"Insufficient stock for product {product_id}: "
            f"requested {requested}, available {available}"
        )
        self.product_id = product_id


def get_item(db: Session, product_id: int) -> InventoryItem:
    item = db.get(InventoryItem, product_id)
    if item is None:
        raise UnknownProduct(product_id)
    return item


def ensure_item(db: Session, product_id: int) -> InventoryItem:
    """Crea la riga di magazzino di un prodotto se non c'e' gia'.

    Chiamata dal consumer di `product.created`: un prodotto appena nato non
    ha pezzi in magazzino finche' non arrivano davvero, quindi la riga parte
    da zero disponibili. Serve comunque crearla subito, perche' e' la
    differenza fra "prodotto senza scorte" (rifiuto legittimo) e "prodotto
    che il magazzino non conosce affatto" — che era il caso in cui ogni
    ordine veniva annullato senza che nessuno sapesse perche'.

    Idempotente: Kafka puo' consegnare lo stesso evento piu' volte, e
    ricreare la riga azzererebbe scorte gia' dichiarate.
    """
    item = db.get(InventoryItem, product_id)
    if item is not None:
        logger.info("Inventory row for product %s already exists: left untouched", product_id)
        return item

    item = InventoryItem(product_id=product_id, quantity_available=0, quantity_reserved=0)
    db.add(item)
    db.commit()
    db.refresh(item)
    logger.info("Inventory row created for product %s (0 available)", product_id)
    return item


def set_available(db: Session, product_id: int, quantity: int) -> InventoryItem:
    """Dichiara quante unita' sono disponibili a magazzino.

    E' l'operazione con cui il magazzino registra una fornitura o corregge
    un conteggio: tocca solo le scorte disponibili e lascia intatte quelle
    gia' riservate da ordini in corso, che sono impegnate altrove.

    Crea la riga se non esiste: i prodotti nati prima dell'evento
    `product.created` non ne hanno una, e senza questo non ci sarebbe modo
    di dargliela se non scrivendo nel database a mano.
    """
    item = db.get(InventoryItem, product_id)
    if item is None:
        item = InventoryItem(product_id=product_id, quantity_available=0, quantity_reserved=0)
        db.add(item)

    item.quantity_available = quantity
    db.commit()
    db.refresh(item)
    return item


def reserve(db: Session, product_id: int, quantity: int, order_id: int = None) -> Reservation:
    """Riserva una singola quantita'. Il chiamante fa il commit."""
    item = get_item(db, product_id)
    if item.quantity_available < quantity:
        raise InsufficientStock(product_id, quantity, item.quantity_available)

    item.quantity_available -= quantity
    item.quantity_reserved += quantity

    reservation = Reservation(product_id=product_id, quantity=quantity, order_id=order_id)
    db.add(reservation)
    return reservation


def release(db: Session, reservation: Reservation) -> None:
    """Rilascia una prenotazione ripristinando le scorte. Il chiamante fa il commit."""
    item = db.get(InventoryItem, reservation.product_id)
    if item is not None:
        item.quantity_available += reservation.quantity
        item.quantity_reserved -= reservation.quantity
    db.delete(reservation)


def reservations_for_order(db: Session, order_id: int) -> List[Reservation]:
    return db.query(Reservation).filter(Reservation.order_id == order_id).all()


def reserve_order(db: Session, order_id: int, items: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Riserva tutti gli item di un ordine, tutto-o-niente.

    Se anche un solo item non e' disponibile solleva un'eccezione e non
    lascia riservato nulla: una riserva parziale bloccherebbe scorte per un
    ordine che la saga sta comunque per annullare.

    Idempotente: se l'ordine risulta gia' riservato (Kafka puo' consegnare
    lo stesso evento piu' volte) restituisce le prenotazioni esistenti
    senza toccare le scorte.
    """
    existing = reservations_for_order(db, order_id)
    if existing:
        logger.info("Order %s already reserved: %d reservation(s)", order_id, len(existing))
        return [_as_dict(r) for r in existing]

    try:
        reservations = [
            reserve(db, int(item["productId"]), int(item["quantity"]), order_id=order_id)
            for item in items
        ]
        db.commit()
    except Exception:
        db.rollback()
        raise

    return [_as_dict(r) for r in reservations]


def release_order(db: Session, order_id: int) -> List[Dict[str, Any]]:
    """Compensazione: rilascia tutte le prenotazioni di un ordine.

    Restituisce una lista vuota se non c'e' nulla da rilasciare — caso
    normale quando l'ordine e' stato annullato proprio perche' le scorte
    non bastavano.
    """
    reservations = reservations_for_order(db, order_id)
    if not reservations:
        return []

    released = [_as_dict(r) for r in reservations]
    for reservation in reservations:
        release(db, reservation)
    db.commit()
    return released


def _as_dict(reservation: Reservation) -> Dict[str, Any]:
    return {
        "reservationId": reservation.id,
        "productId": reservation.product_id,
        "quantity": reservation.quantity,
    }
