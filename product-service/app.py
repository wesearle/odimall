import os
import time
import logging
import urllib.parse

from flask import Flask, jsonify, request
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

_openai_client = None


def ai_mode():
    """demo | openai | gemini (see ODIMALL_AI_MODE)."""
    return os.getenv("ODIMALL_AI_MODE", "demo").strip().lower()


def demo_ai_summary(product, tone):
    """Deterministic storefront copy for demos — no network, no tokens, no GenAI spans."""
    name = product["name"]
    cat = product["category"]
    price = product["price"]
    desc = product["description"].strip()
    snippet = desc if len(desc) <= 320 else desc[:317].rstrip() + "…"
    return (
        f"The {name} is a solid pick in {cat} at ${price:.2f}. {snippet} "
        f"We tuned this blurb for a {tone} feel using OdiGear's built-in demo writer (no cloud LLM call)."
    )


def get_openai_client():
    """Lazy OpenAI client; Odigos Python distro auto-instruments the official SDK (openai-v2)."""
    global _openai_client
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        return None
    if _openai_client is None:
        from openai import OpenAI

        _openai_client = OpenAI(api_key=api_key)
    return _openai_client


def build_copywriter_prompt(product, tone):
    return (
        f"Product catalog entry:\n"
        f"- Name: {product['name']}\n"
        f"- Category: {product['category']}\n"
        f"- Price (USD): {product['price']:.2f}\n"
        f"- Current description: {product['description']}\n\n"
        f"Write a single paragraph ({tone}) suitable for an e-commerce product card. "
        f"Do not invent specs or price; stay grounded in the fields above."
    )


def generate_with_gemini(user_prompt):
    from google import genai
    from google.genai import types

    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        return None, "Gemini is not configured"
    model_name = os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip() or "gemini-2.5-flash"
    client = genai.Client(api_key=api_key)
    response = client.models.generate_content(
        model=model_name,
        contents=user_prompt,
        config=types.GenerateContentConfig(
            system_instruction="You are a helpful retail copywriter for OdiGear outdoor gear.",
            max_output_tokens=256,
            temperature=0.7,
        ),
    )
    text_out = (response.text or "").strip() if response else ""
    return text_out, None


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


@app.route("/products/<int:product_id>/ai-summary", methods=["POST"])
def ai_product_summary(product_id):
    """
    Merchandising blurb: ODIMALL_AI_MODE demo | openai | gemini (env + README).
    """
    with engine.connect() as conn:
        result = conn.execute(
            text("SELECT id, name, description, price, image_url, category FROM products WHERE id = :pid"),
            {"pid": product_id},
        )
        row = result.fetchone()
    if row is None:
        log.warning("AI summary requested for missing product %d", product_id)
        return jsonify({"error": "Product not found"}), 404

    product = row_to_product(row)
    extra = request.get_json(silent=True) or {}
    tone = (extra.get("tone") or "friendly and concise").strip()

    mode = ai_mode()
    user_prompt = build_copywriter_prompt(product, tone)

    if mode == "demo":
        text_out = demo_ai_summary(product, tone)
        log.info("AI demo-local summary for product %d", product_id)
        return jsonify(
            {
                "productId": product["id"],
                "name": product["name"],
                "category": product["category"],
                "price": product["price"],
                "aiSummary": text_out,
                "model": "odigear-demo-local",
                "source": "demo-local",
            }
        )

    if mode == "gemini":
        if not os.getenv("GEMINI_API_KEY", "").strip():
            return (
                jsonify(
                    {
                        "error": "Gemini is not configured",
                        "hint": "Set GEMINI_API_KEY (e.g. enable productService.gemini in Helm and patch the Secret).",
                    }
                ),
                503,
            )
        try:
            text_out, err = generate_with_gemini(user_prompt)
        except Exception as err:
            log.exception("Gemini request failed for product %s", product_id)
            detail = str(err)
            hint = None
            if "404" in detail and "no longer available" in detail.lower():
                hint = (
                    "Google returned 404 for this model id. New API keys often cannot use "
                    "gemini-2.0-flash; set GEMINI_MODEL / Helm productService.geminiModel to "
                    "gemini-2.5-flash (or gemini-2.5-flash-lite) and restart product-service."
                )
            body = {"error": "Gemini request failed", "detail": detail}
            if hint:
                body["hint"] = hint
            return jsonify(body), 502
        if err:
            return jsonify({"error": err}), 503
        if not text_out:
            return jsonify({"error": "Empty completion from Gemini"}), 502
        gmodel = os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip() or "gemini-2.5-flash"
        log.info("AI summary for product %d model=%s (gemini)", product_id, gmodel)
        return jsonify(
            {
                "productId": product["id"],
                "name": product["name"],
                "category": product["category"],
                "price": product["price"],
                "aiSummary": text_out,
                "model": gmodel,
                "source": "gemini",
            }
        )

    if mode != "openai":
        log.warning("Unknown ODIMALL_AI_MODE=%r, using demo-local", mode)
        text_out = demo_ai_summary(product, tone)
        return jsonify(
            {
                "productId": product["id"],
                "name": product["name"],
                "category": product["category"],
                "price": product["price"],
                "aiSummary": text_out,
                "model": "odigear-demo-local",
                "source": "demo-local",
            }
        )

    client = get_openai_client()
    if client is None:
        return (
            jsonify(
                {
                    "error": "OpenAI is not configured",
                    "hint": "Set OPENAI_API_KEY (e.g. from a Kubernetes secret).",
                }
            ),
            503,
        )

    model = os.getenv("OPENAI_MODEL", "gpt-4o-mini").strip() or "gpt-4o-mini"

    try:
        completion = client.chat.completions.create(
            model=model,
            messages=[
                {
                    "role": "system",
                    "content": "You are a helpful retail copywriter for OdiGear outdoor gear.",
                },
                {"role": "user", "content": user_prompt},
            ],
            max_tokens=256,
            temperature=0.7,
        )
    except Exception as err:
        log.exception("OpenAI request failed for product %s", product_id)
        return jsonify({"error": "OpenAI request failed", "detail": str(err)}), 502

    choice = completion.choices[0] if completion.choices else None
    text_out = (choice.message.content if choice and choice.message else "").strip()
    if not text_out:
        return jsonify({"error": "Empty completion from OpenAI"}), 502

    usage = completion.usage
    log.info(
        "AI summary for product %d model=%s tokens in=%s out=%s",
        product_id,
        model,
        getattr(usage, "prompt_tokens", None) if usage else None,
        getattr(usage, "completion_tokens", None) if usage else None,
    )

    return jsonify(
        {
            "productId": product["id"],
            "name": product["name"],
            "category": product["category"],
            "price": product["price"],
            "aiSummary": text_out,
            "model": model,
            "source": "openai",
        }
    )


def main():
    connect_with_retry()
    app.run(host="0.0.0.0", port=8082, debug=False)


if __name__ == "__main__":
    main()
