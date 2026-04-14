import os
import time
import logging
import urllib.parse

from flask import Flask, jsonify
from sqlalchemy import create_engine, event, text
from google.cloud.sqlcommenter.sqlalchemy.executor import BeforeExecuteFactory

try:
    from opentelemetry.trace.propagation.tracecontext import (
        TraceContextTextMapPropagator,
    )
    propagator = TraceContextTextMapPropagator()
except ImportError:
    propagator = None

app = Flask(__name__)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("product-service")

db_host = os.getenv("DB_HOST", "mysql")
db_port = os.getenv("DB_PORT", "3306")
db_user = os.getenv("DB_USER", "odimall")
db_pass = urllib.parse.quote_plus(os.getenv("DB_PASSWORD", "odimall123"))
db_name = os.getenv("DB_NAME", "odimall")
db_url = f"mysql+pymysql://{db_user}:{db_pass}@{db_host}:{db_port}/{db_name}"

engine = None


def connect_with_retry(max_wait=30, interval=2):
    global engine
    deadline = time.time() + max_wait
    while True:
        try:
            engine = create_engine(db_url, pool_size=5, pool_pre_ping=True)
            with engine.connect() as conn:
                conn.execute(text("SELECT 1"))
            log.info("Connected to MySQL")

            if propagator is not None:
                log.info("Using OpenTelemetry auto-instrumentation for SQL commenter")
                listener = BeforeExecuteFactory(with_opentelemetry=True)
            else:
                log.info("Running without OpenTelemetry — SQL commenter without trace context")
                listener = BeforeExecuteFactory()

            event.listen(engine, "before_cursor_execute", listener, retval=True)
            return
        except Exception as err:
            if time.time() + interval > deadline:
                log.error("Could not connect to MySQL after %ss: %s", max_wait, err)
                raise
            log.warning("MySQL not ready, retrying in %ss … (%s)", interval, err)
            time.sleep(interval)


def row_to_product(row):
    return {
        "id": row[0],
        "name": row[1],
        "description": row[2],
        "price": float(row[3]),
        "imageUrl": row[4],
        "category": row[5],
    }


@app.route("/products", methods=["GET"])
def list_products():
    with engine.connect() as conn:
        result = conn.execute(
            text("SELECT id, name, description, price, image_url, category FROM products")
        )
        products = [row_to_product(r) for r in result]
    log.info("Listed %d products", len(products))
    return jsonify(products)


@app.route("/products/<int:product_id>", methods=["GET"])
def get_product(product_id):
    with engine.connect() as conn:
        result = conn.execute(
            text("SELECT id, name, description, price, image_url, category FROM products WHERE id = :pid"),
            {"pid": product_id},
        )
        row = result.fetchone()
    if row is None:
        log.warning("Product %d not found", product_id)
        return jsonify({"error": "Product not found"}), 404
    log.info("Fetched product %d", product_id)
    return jsonify(row_to_product(row))


def main():
    connect_with_retry()
    app.run(host="0.0.0.0", port=8082, debug=False)


if __name__ == "__main__":
    main()
