"""
EzzyHub — HF Inference API Validator
=====================================
Uses the Hugging Face Inference API (free tier) to validate requirements
as an alternative or fallback to Groq.

Why HF Inference API instead of Groq?
  - No separate account needed beyond HF (which we already use for models)
  - Free tier: ~30,000 requests/month
  - Same token (HF_TOKEN) used for model downloads and API calls
  - Multiple model options: Mistral-7B, Zephyr, Llama-3

Setup:
  1. Get free token: https://huggingface.co/settings/tokens
  2. export HF_TOKEN=hf_xxxxxxxxxxxx

Fallback chain:
  Groq (if GROQ_API_KEY set) → HF Inference API (if HF_TOKEN set) → rule-based
"""

import json
import logging
import os
import re
from typing import Optional

import httpx

logger = logging.getLogger(__name__)

HF_TOKEN = os.getenv("HF_TOKEN", "")
HF_API_URL = "https://api-inference.huggingface.co/models/{model}/v1/chat/completions"

# Primary model: Mistral-7B-Instruct — reliable JSON, fast
# Fallback model: zephyr-7b-beta — lighter, sometimes better at structured output
PRIMARY_MODEL   = "mistralai/Mistral-7B-Instruct-v0.2"
FALLBACK_MODEL  = "HuggingFaceH4/zephyr-7b-beta"

SYSTEM_PROMPT = (
    "You are a requirements engineering expert. "
    "Respond ONLY with a raw JSON object, no markdown, no backticks."
)

USER_TEMPLATE = """\
Analyze this software requirement: "{text}"

Return ONLY this JSON (no markdown, no explanation):
{{"isRequirement":true,"isClear":true,"hasMultiple":false,"confidence":0.9,"reason":""}}

Rules:
- isRequirement: true if it describes a specific system behavior or constraint
- isClear: true if unambiguous and specific enough to implement
- hasMultiple: true if it bundles 2+ distinct requirements
- confidence: 0.0-1.0 score (how valid and clear)
- reason: brief explanation if invalid, otherwise empty string"""


class HFValidator:
    """
    Validates requirements via HF Inference API (Mistral-7B).
    Thread-safe — uses a shared httpx client with connection pooling.
    """

    def __init__(self):
        self._client = httpx.Client(timeout=30.0)
        self.available = bool(HF_TOKEN and not HF_TOKEN.startswith("your_"))
        if not self.available:
            logger.warning(
                "HF_TOKEN not set — HF Inference API validation disabled. "
                "Set HF_TOKEN=hf_... to enable."
            )
        else:
            logger.info("✓ HF Inference API validator ready (model: %s)", PRIMARY_MODEL)

    def validate(self, text: str) -> dict:
        """
        Returns a dict with keys: valid, confidence, reason, multiple_reqs
        Falls back to rule-based if API is unavailable.
        """
        if not self.available:
            return self._rule_based(text)

        result = self._call_api(text, PRIMARY_MODEL)
        if result is None:
            logger.warning("Primary model failed, trying fallback model...")
            result = self._call_api(text, FALLBACK_MODEL)
        if result is None:
            logger.warning("Both HF models failed, using rule-based validation")
            return self._rule_based(text)
        return result

    def _call_api(self, text: str, model: str) -> Optional[dict]:
        url = HF_API_URL.format(model=model)
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": USER_TEMPLATE.format(text=text)},
            ],
            "max_tokens": 200,
            "temperature": 0.1,
        }
        try:
            resp = self._client.post(
                url,
                json=payload,
                headers={
                    "Authorization": f"Bearer {HF_TOKEN}",
                    "Content-Type":  "application/json",
                },
            )
            resp.raise_for_status()
            raw = resp.json()["choices"][0]["message"]["content"].strip()
            return self._parse_json(raw)
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 503:
                logger.warning("HF model %s loading (503) — retry in a moment", model)
            else:
                logger.error("HF API HTTP %s for %s: %s", e.response.status_code, model, e.response.text[:200])
        except Exception as e:
            logger.error("HF API call failed (%s): %s", model, e)
        return None

    @staticmethod
    def _parse_json(raw: str) -> Optional[dict]:
        # Strip markdown fences if present
        raw = re.sub(r"```json|```", "", raw).strip()
        s = raw.find("{"); e = raw.rfind("}")
        if s < 0 or e < 0:
            return None
        try:
            j = json.loads(raw[s:e+1])
            is_req   = bool(j.get("isRequirement", True))
            is_clear = bool(j.get("isClear", True))
            return {
                "valid":         is_req and is_clear,
                "confidence":    float(j.get("confidence", 0.7)),
                "reason":        j.get("reason", "") or "",
                "multiple_reqs": bool(j.get("hasMultiple", False)),
            }
        except (json.JSONDecodeError, KeyError, TypeError):
            return None

    @staticmethod
    def _rule_based(text: str) -> dict:
        """Minimal rule-based fallback — runs when both APIs are down."""
        words = text.lower().split()
        word_count = len(words)
        action_words = {"allow", "enable", "provide", "support", "ensure", "validate",
                        "verify", "generate", "display", "store", "retrieve", "detect",
                        "classify", "match", "analyze", "accept", "reject", "notify"}
        has_action   = any(w in action_words for w in words)
        confidence   = 0.75 if (word_count >= 5 and has_action) else 0.40
        return {
            "valid":         word_count >= 5 and has_action,
            "confidence":    confidence,
            "reason":        "" if confidence > 0.5 else "Too short or no action verb",
            "multiple_reqs": False,
        }
