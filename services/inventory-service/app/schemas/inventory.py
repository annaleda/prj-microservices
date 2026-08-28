from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class InventoryResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, from_attributes=True)

    product_id: int = Field(alias="productId")
    quantity_available: int = Field(alias="quantityAvailable")
    quantity_reserved: int = Field(alias="quantityReserved")


class ReservationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    product_id: int = Field(alias="productId")
    quantity: int = Field(gt=0)


class ReservationResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, from_attributes=True)

    id: int
    product_id: int = Field(alias="productId")
    quantity: int
    created_at: datetime = Field(alias="createdAt")
