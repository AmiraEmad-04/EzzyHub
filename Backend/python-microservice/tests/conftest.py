"""
Shared pytest setup for the NLP microservice tests.

These env vars MUST be set before `main`/`embedder` are imported by any test
module, so they live at conftest module load time (pytest imports conftest first):

  * DEV_MODE=true      -> lets `main` import without an NLP_SERVICE_KEY and makes
                          the require_key dependency a no-op (no auth header needed).
  * EMBED_CACHE_DIR    -> points embedder's diskcache at a throwaway temp dir instead
                          of writing a .cache tree inside the repo.

The heavy ML models are never loaded: tests import the app but do NOT run the
FastAPI lifespan (TestClient is used without a `with` block), and instead inject
lightweight fakes for the model globals.
"""

import os
import tempfile

os.environ.setdefault("DEV_MODE", "true")
os.environ.setdefault("EMBED_CACHE_DIR", tempfile.mkdtemp(prefix="ezzyhub-embed-cache-"))
