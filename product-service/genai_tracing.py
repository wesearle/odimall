"""Enrich Odigos google-genai spans with usage fields the OTel helper omits."""
import logging

log = logging.getLogger("product-service")

_installed = False


def install_gemini_usage_enrichment() -> None:
    """Patch OTel google-genai helper to add thoughts/total token span attributes."""
    global _installed
    if _installed:
        return

    try:
        from opentelemetry.instrumentation.google_genai.generate_content import (
            _GenerateContentInstrumentationHelper,
            _get_response_property,
        )
    except ImportError as err:
        log.warning("Gemini usage enrichment unavailable: %s", err)
        return

    if getattr(_GenerateContentInstrumentationHelper, "_odimall_patched", False):
        _installed = True
        return

    original_update = _GenerateContentInstrumentationHelper._maybe_update_token_counts
    original_final = _GenerateContentInstrumentationHelper.create_final_attributes

    def _maybe_update_token_counts(self, response):
        original_update(self, response)
        total = _get_response_property(response, "usage_metadata.total_token_count")
        if isinstance(total, int):
            self._total_tokens = total
        thoughts = _get_response_property(response, "usage_metadata.thoughts_token_count")
        if isinstance(thoughts, int):
            self._thoughts_tokens = thoughts

    def create_final_attributes(self):
        attrs = original_final(self)
        thinking = getattr(self, "_thoughts_tokens", None)
        if thinking is None:
            thinking = getattr(self, "_thinking_tokens", None)
        if isinstance(thinking, int) and thinking > 0:
            attrs["gen_ai.usage.thoughts_tokens"] = thinking
            attrs["gen_ai.usage.reasoning.output_tokens"] = thinking
        total = getattr(self, "_total_tokens", None)
        if total is None:
            inp = getattr(self, "_input_tokens", 0) or 0
            out = getattr(self, "_output_tokens", 0) or 0
            if inp or out:
                total = inp + out
        if isinstance(total, int) and total > 0:
            attrs["gen_ai.usage.total_tokens"] = total
        return attrs

    _GenerateContentInstrumentationHelper._maybe_update_token_counts = (
        _maybe_update_token_counts
    )
    _GenerateContentInstrumentationHelper.create_final_attributes = create_final_attributes
    _GenerateContentInstrumentationHelper._odimall_patched = True

    _installed = True
    log.info("Gemini usage enrichment patched google-genai instrumentation helper")
