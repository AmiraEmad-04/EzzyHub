"""
verify_embedding_fix.py — run this INSIDE the python-microservice venv to prove
the long-requirement zero-vector bug is fixed on the REAL model.

    cd python-microservice
    .\\venv\\Scripts\\Activate.ps1
    python verify_embedding_fix.py

It loads the SAME code model the service uses and embeds one short and one long
requirement, BEFORE and AFTER setting max_seq_length. You will see the long one
go from a zero vector to a real vector.
"""
import numpy as np
from sentence_transformers import SentenceTransformer

SHORT = "The system shall allow a user to log out."
LONG  = ("The system shall allow a new user to register an account using username, "
         "email, and password, validating required fields and email format, preventing "
         "duplicate accounts, and securely storing credentials.")

def norm(v):
    return float(np.linalg.norm(np.asarray(v, dtype=float)))

print("Loading krlvi/sentence-t5-base-nlpl-code_search_net ...")
m = SentenceTransformer("krlvi/sentence-t5-base-nlpl-code_search_net")

print("\n--- BEFORE fix (default max_seq_length =", m.max_seq_length, ") ---")
for label, txt in [("SHORT", SHORT), ("LONG", LONG)]:
    v = m.encode(txt, convert_to_numpy=True, normalize_embeddings=True)
    print(f"  {label:5} len={len(txt):3d}  norm={norm(v):.4f}  {'ZERO!!' if norm(v)==0 else 'ok'}")

print("\n--- AFTER fix (max_seq_length = 256) ---")
m.max_seq_length = 256
for label, txt in [("SHORT", SHORT), ("LONG", LONG)]:
    v = m.encode(txt, convert_to_numpy=True, normalize_embeddings=True)
    print(f"  {label:5} len={len(txt):3d}  norm={norm(v):.4f}  {'ZERO!!' if norm(v)==0 else 'ok'}")

print("\nIf LONG went from norm=0.0000 (ZERO) to a nonzero norm, the fix is confirmed.")
print("If LONG was already nonzero BEFORE, the zeros were purely a CACHE issue —")
print("in that case just clear the embedding cache and the fixed embed_batch handles it.")
