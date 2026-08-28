from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.database import Base, SessionLocal, engine
from app.models import InventoryItem
from app.routers.inventory import router as inventory_router

# Nota: il documento di design prevede che questo servizio consumi/pubblichi
# eventi Kafka (order.created, inventory.reserved, inventory.rejected,
# inventory.released). Kafka non e' ancora presente nel progetto (Phase 3
# della roadmap): per ora l'esito delle prenotazioni e' riflesso solo dal
# codice di stato HTTP (201/404/409). Il producer/consumer Kafka verra'
# aggiunto quando l'infrastruttura di eventi sara' pronta.

_SEED_STOCK = {1: 100, 2: 50, 3: 30}


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        for product_id, quantity in _SEED_STOCK.items():
            if db.get(InventoryItem, product_id) is None:
                db.add(InventoryItem(product_id=product_id, quantity_available=quantity, quantity_reserved=0))
        db.commit()
    yield


app = FastAPI(title="Inventory Service", version="0.1.0", lifespan=lifespan)
app.include_router(inventory_router, prefix="/api/inventory", tags=["inventory"])


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}
