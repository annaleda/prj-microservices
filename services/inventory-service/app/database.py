import os

from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker


def _database_url() -> str:
    host = os.getenv("INVENTORY_DB_HOST", "localhost")
    port = os.getenv("INVENTORY_DB_PORT", "5437")
    name = os.getenv("INVENTORY_DB_NAME", "inventory")
    user = os.getenv("INVENTORY_DB_USER", "inventory")
    password = os.getenv("INVENTORY_DB_PASSWORD", "inventory")
    return f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{name}"


engine = create_engine(_database_url(), pool_pre_ping=True)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
