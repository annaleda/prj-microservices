import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlalchemy import text

from app.database import Base, SessionLocal, engine
from app.events import producer
from app.events.consumer import SagaConsumer
from app.models import InventoryItem
from app.routers.inventory import router as inventory_router

logging.basicConfig(level=logging.INFO)

# Righe di magazzino minime create all'avvio.
#
# NON e' l'inventario del catalogo: sono tre prodotti fittizi su cui si
# appoggiano i test delle API e con cui il servizio e' provabile appena
# avviato, anche senza gli altri microservizi accesi.
#
# Le scorte dei prodotti veri si dichiarano con PUT /api/inventory/{id}
# (script infrastructure/demo/seed-stock.sh, che legge il catalogo dalle
# sue API e non deve percio' conoscerne gli identificativi). Una copia a
# mano degli id del catalogo dentro questo servizio e' esattamente cio' che
# il 5 settembre 2026 ha fatto annullare ogni ordine sui nuovi prodotti:
# il catalogo era cresciuto, questa lista no.
_SEED_STOCK = {1: 100, 2: 50, 3: 30}


def _events_enabled() -> bool:
    """Il consumer Kafka si puo' spegnere (EVENTS_ENABLED=false) per usare il
    servizio come sola API REST, ad esempio nei test che non hanno un broker."""
    return os.getenv("EVENTS_ENABLED", "true").lower() not in ("false", "0", "no")


def _apply_schema() -> None:
    Base.metadata.create_all(bind=engine)
    # create_all crea le tabelle mancanti ma non altera quelle esistenti:
    # reservations.order_id e' stata aggiunta dopo il primo rilascio del
    # servizio, quindi va aggiunta a mano sui database gia' creati. In un
    # progetto reale questo sarebbe compito di uno strumento di migrazione
    # (Alembic), non ancora presente nel progetto.
    with engine.begin() as connection:
        connection.execute(text("ALTER TABLE reservations ADD COLUMN IF NOT EXISTS order_id INTEGER"))


def _seed_stock() -> None:
    with SessionLocal() as db:
        for product_id, quantity in _SEED_STOCK.items():
            if db.get(InventoryItem, product_id) is None:
                db.add(InventoryItem(product_id=product_id, quantity_available=quantity, quantity_reserved=0))
        db.commit()


@asynccontextmanager
async def lifespan(app: FastAPI):
    _apply_schema()
    _seed_stock()

    consumer = None
    if _events_enabled():
        consumer = SagaConsumer()
        consumer.start()

    yield

    if consumer is not None:
        consumer.stop()
        consumer.join(timeout=10)
    producer.shutdown()


app = FastAPI(title="Inventory Service", version="0.1.0", lifespan=lifespan)
app.include_router(inventory_router, prefix="/api/inventory", tags=["inventory"])


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}
