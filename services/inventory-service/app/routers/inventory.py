from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.models import InventoryItem, Reservation
from app.schemas.inventory import InventoryResponse, ReservationRequest, ReservationResponse
from app.services import inventory as inventory_service

router = APIRouter()


@router.get("/{product_id}", response_model=InventoryResponse)
def get_inventory(product_id: int, db: Session = Depends(get_db)) -> InventoryItem:
    try:
        return inventory_service.get_item(db, product_id)
    except inventory_service.UnknownProduct as error:
        raise HTTPException(status_code=404, detail=str(error))


@router.post("/reservations", response_model=ReservationResponse, status_code=status.HTTP_201_CREATED)
def create_reservation(request: ReservationRequest, db: Session = Depends(get_db)) -> Reservation:
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
def release_reservation(reservation_id: int, db: Session = Depends(get_db)) -> None:
    reservation = db.get(Reservation, reservation_id)
    if reservation is None:
        raise HTTPException(status_code=404, detail=f"Reservation not found: {reservation_id}")

    inventory_service.release(db, reservation)
    db.commit()
