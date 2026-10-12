# EzzyHub Python NLP Microservice — Dockerfile
# Multi-stage build: keeps the final image lean
# Downloads HF models at BUILD time so cold starts are instant

FROM python:3.11-slim AS builder

WORKDIR /app

# System deps for sentence-transformers + spaCy
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc g++ curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python deps — layer-cached when requirements.txt unchanged
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

# Download spaCy English model
RUN python -m spacy download en_core_web_sm

# Pre-download HF models at build time
# They are cached in /root/.cache/huggingface/ which we copy to the final stage.
# This means: docker build is slow (downloads ~500 MB),
# but docker run starts in seconds (no download on container start).
ARG HF_TOKEN=""
ENV HF_TOKEN=$HF_TOKEN
RUN python - <<'EOF'
from sentence_transformers import SentenceTransformer, CrossEncoder
import logging
logging.basicConfig(level=logging.INFO)

print("Downloading all-MiniLM-L6-v2...")
SentenceTransformer("all-MiniLM-L6-v2")

print("Downloading BAAI/bge-small-en-v1.5...")
SentenceTransformer("BAAI/bge-small-en-v1.5")

print("Downloading sentence-t5-base-nlpl-code_search_net...")
SentenceTransformer("krlvi/sentence-t5-base-nlpl-code_search_net")

print("Downloading cross-encoder/ms-marco-MiniLM-L-6-v2...")
CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")

print("All models downloaded.")
EOF

# ── Final stage ───────────────────────────────────────────────────────────────
FROM python:3.11-slim

WORKDIR /app

# Security: run as non-root
RUN groupadd -r nlpuser && useradd -r -g nlpuser nlpuser

# Copy installed packages from builder
COPY --from=builder /usr/local/lib/python3.11/site-packages /usr/local/lib/python3.11/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin
COPY --from=builder /root/.cache /home/nlpuser/.cache
COPY --from=builder /usr/lib/python3/dist-packages /usr/lib/python3/dist-packages

# Copy spaCy model
COPY --from=builder /usr/local/lib/python3.11/site-packages/en_core_web_sm \
                    /usr/local/lib/python3.11/site-packages/en_core_web_sm

# Copy source
COPY *.py ./

# Fix cache ownership
RUN chown -R nlpuser:nlpuser /app /home/nlpuser/.cache

USER nlpuser

# Embedding disk cache directory
RUN mkdir -p /app/cache

EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=5 --start-period=60s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"

CMD ["uvicorn", "main:app", \
     "--host", "0.0.0.0", \
     "--port", "8000", \
     "--workers", "2", \
     "--timeout-keep-alive", "300"]
