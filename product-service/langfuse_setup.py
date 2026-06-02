"""Optional Langfuse tracing for LLM calls (OpenAI + Google Gemini).

Coexists with Odigos Python/eBPF auto-instrumentation: Langfuse uses an isolated
TracerProvider so it does not replace Odigos' global OpenTelemetry setup.
"""
import functools
import logging
import os
import sys

log = logging.getLogger("product-service")

_observe = None
_langfuse_client = None
_langfuse_ready = False


def _looks_like_placeholder(value):
    v = (value or "").strip().lower()
    return not v or "replace-me" in v or v.endswith("-your-langfuse-public-key") or v.endswith(
        "-your-langfuse-secret-key"
    )


def _langfuse_configured():
    flag = os.getenv("LANGFUSE_ENABLED", "").strip().lower()
    if flag in ("false", "0", "no"):
        return False
    pk = os.getenv("LANGFUSE_PUBLIC_KEY", "").strip()
    sk = os.getenv("LANGFUSE_SECRET_KEY", "").strip()
    if _looks_like_placeholder(pk) or _looks_like_placeholder(sk):
        return False
    return bool(pk) and bool(sk)


def langfuse_enabled():
    return _langfuse_ready


def _prepare_pip_opentelemetry():
    """
    Odigos injects an older OpenTelemetry SDK under /var/odigos/python.
    Langfuse v4 needs the PyPI packages — deprioritize Odigos paths and reload
    OTEL modules so imports resolve to site-packages, without touching Odigos eBPF.
    """
    odigos_paths = [p for p in sys.path if "/var/odigos/python" in p]
    if not odigos_paths:
        return
    sys.path[:] = [p for p in sys.path if "/var/odigos/python" not in p] + odigos_paths
    for name in list(sys.modules):
        if name == "opentelemetry" or name.startswith("opentelemetry."):
            del sys.modules[name]


def observe_llm(*args, **kwargs):
    """Apply @observe when Langfuse is configured; otherwise no-op.

    Decorators run at import time, before init_langfuse() in main(), so wrap lazily
    on first call once Langfuse is ready.
    """
    observe_args = args
    observe_kwargs = kwargs

    def decorator(fn):
        wrapped = None

        @functools.wraps(fn)
        def wrapper(*call_args, **call_kwargs):
            nonlocal wrapped
            if _observe is not None:
                if wrapped is None:
                    wrapped = _observe(*observe_args, **observe_kwargs)(fn)
                return wrapped(*call_args, **call_kwargs)
            return fn(*call_args, **call_kwargs)

        return wrapper

    if args and callable(args[0]):
        return decorator(args[0])
    return decorator


def init_langfuse():
    global _observe, _langfuse_client, _langfuse_ready
    if _langfuse_ready:
        return
    if not _langfuse_configured():
        pk = os.getenv("LANGFUSE_PUBLIC_KEY", "").strip()
        sk = os.getenv("LANGFUSE_SECRET_KEY", "").strip()
        if _looks_like_placeholder(pk) or _looks_like_placeholder(sk):
            log.warning(
                "Langfuse keys are empty or still Helm placeholders — patch Secret and restart"
            )
        else:
            log.info("Langfuse disabled (set LANGFUSE_PUBLIC_KEY + LANGFUSE_SECRET_KEY to enable)")
        return

    try:
        _prepare_pip_opentelemetry()
        from opentelemetry.sdk.trace import TracerProvider
        from langfuse import Langfuse, observe

        # Isolated provider — Odigos keeps the global TracerProvider / eBPF pipeline.
        _langfuse_client = Langfuse(tracer_provider=TracerProvider())
        _observe = observe
        try:
            if not _langfuse_client.auth_check():
                log.warning("Langfuse auth check failed — verify keys and LANGFUSE_BASE_URL")
        except Exception as err:
            log.warning("Langfuse auth check error: %s", err)

        mode = os.getenv("ODIMALL_AI_MODE", "demo").strip().lower()
        # @observe on generate_with_gemini captures LLM spans for Langfuse; Odigos eBPF
        # instruments outbound GenAI separately — skip openinference to save memory.

        base = os.getenv("LANGFUSE_BASE_URL", "https://cloud.langfuse.com")
        _langfuse_ready = True
        log.info(
            "Langfuse tracing enabled with isolated TracerProvider (base_url=%s, ai_mode=%s)",
            base,
            mode,
        )
    except Exception as err:
        log.warning(
            "Langfuse init failed (%s); continuing without Langfuse tracing. "
            "AI blurbs still work; Odigos instrumentation is unaffected.",
            err,
        )


def flush_langfuse():
    if not _langfuse_ready or _langfuse_client is None:
        return
    try:
        _langfuse_client.flush()
    except Exception:
        pass
