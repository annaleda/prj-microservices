from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.models import InventoryItem, Reservation
from app.schemas.inventory import (
    InventoryResponse,
    ReservationRequest,
    ReservationResponse,
    StockUpdateRequest,
)
from app.security import Principal, current_principal, require_roles
from app.services import inventory as inventory_service

router = APIRouter()

# Le scorte si muovono a mano solo dal magazzino (o da un amministratore):
# il percorso normale e' la saga, che passa da Kafka e non da queste API.
warehouse_only = require_roles("WAREHOUSE", "ADMIN")


@router.get("/{product_id}", response_model=InventoryResponse)
def get_inventory(
    product_id: int,
    db: Session = Depends(get_db),
    principal: Principal = Depends(current_principal),
) -> InventoryItem:
    try:
        return inventory_service.get_item(db, product_id)
    except inventory_service.UnknownProduct as error:
        raise HTTPException(status_code=404, detail=str(error))


@router.put("/{product_id}", response_model=InventoryResponse)
def set_stock(
    product_id: int,
    request: StockUpdateRequest,
    db: Session = Depends(get_db),
    principal: Principal = Depends(warehouse_only),
) -> InventoryItem:
    """Dichiara le scorte disponibili di un prodotto.

    E' l'unico modo per rifornire un prodotto: la saga sa solo riservare e
    rilasciare, e un prodotto appena creato nasce a zero disponibili. Senza
    questo endpoint le scorte si potrebbero cambiare solo scrivendo
    direttamente nel database.
    """
    return inventory_service.set_available(db, product_id, request.quantity_available)


@router.post("/reservations", response_model=ReservationResponse, status_code=status.HTTP_201_CREATED)
def create_reservation(
    request: ReservationRequest,
    db: Session = Depends(get_db),
    principal: Principal = Depends(warehouse_only),
) -> Reservation:
    try:
        reservation = inventory_service.reserve(db, request.product_id, request.quantity)
    except inventory_service.UnknownProduct as error:
        raise HTTPException(status_code=404, detail=str(error))
    except inventory_service.InsufficientStock as error:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error))

    db.commit()
    db.refresh(reservation)
    return reservation


@router.delete("/reservations/{reservation_id}", status_code=status.HTTP_204_NO_CONTENT)
def release_reservation(
    reservation_id: int,
    db: Session = Depends(get_db),
    principal: Principal = Depends(warehouse_only),
) -> None:
    reservation = db.get(Reservation, reservation_id)
    if reservation is None:
        raise HTTPException(status_code=404, detail=f"Reservation not found: {reservation_id}")

    inventory_service.release(db, reservation)
    db.commit()
