from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.database import get_db
from app.models import InventoryItem, Reservation
from app.schemas.inventory import InventoryResponse, ReservationRequest, ReservationResponse

router = APIRouter()


@router.get("/{product_id}", response_model=InventoryResponse)
def get_inventory(product_id: int, db: Session = Depends(get_db)) -> InventoryItem:
    item = db.get(InventoryItem, product_id)
    if item is None:
        raise HTTPException(status_code=404, detail=f"Inventory not found for product {product_id}")
    return item


@router.post("/reservations", response_model=ReservationResponse, status_code=status.HTTP_201_CREATED)
def create_reservation(request: ReservationRequest, db: Session = Depends(get_db)) -> Reservation:
    item = db.get(InventoryItem, request.product_id)
    if item is None:
        raise HTTPException(status_code=404, detail=f"Inventory not found for product {request.product_id}")

    if item.quantity_available < request.quantity:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"Insufficient stock for product {request.product_id}: "
                f"requested {request.quantity}, available {item.quantity_available}"
            ),
        )

    item.quantity_available -= request.quantity
    item.quantity_reserved += request.quantity

    reservation = Reservation(product_id=request.product_id, quantity=request.quantity)
    db.add(reservation)
    db.commit()
    db.refresh(reservation)
    return reservation


@router.delete("/reservations/{reservation_id}", status_code=status.HTTP_204_NO_CONTENT)
def release_reservation(reservation_id: int, db: Session = Depends(get_db)) -> None:
    reservation = db.get(Reservation, reservation_id)
    if reservation is None:
        raise HTTPException(status_code=404, detail=f"Reservation not found: {reservation_id}")

    item = db.get(InventoryItem, reservation.product_id)
    if item is not None:
        item.quantity_available += reservation.quantity
        item.quantity_reserved -= reservation.quantity

    db.delete(reservation)
    db.commit()
