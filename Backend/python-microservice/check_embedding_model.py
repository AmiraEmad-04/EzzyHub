"""
Run this BEFORE we touch embedder.py — just checks what dimension the
code-search model actually outputs, and does a quick sanity comparison
against all-MiniLM-L6-v2 on a real requirement vs. a real (unrelated) chunk,
so we have real numbers instead of guessing.

Usage:
    python check_embedding_model.py
"""
from sentence_transformers import SentenceTransformer
import numpy as np

print("Loading all-MiniLM-L6-v2 (current model)...")
generic_model = SentenceTransformer("all-MiniLM-L6-v2")

print("Loading krlvi/sentence-t5-base-nlpl-code_search_net (candidate model)...")
code_model = SentenceTransformer("krlvi/sentence-t5-base-nlpl-code_search_net")

req_text = "The health care professional types an invalid medical identification number and is prompted to try again."
related_chunk = "validate MID Validator validate Med Id ( String mid ) — checks if the given medical id is a valid format"
unrelated_chunk = "Connection DAO Factory get Connection (  ) — connection dao factory"

print()
print("Output dimensions:")
print("  all-MiniLM-L6-v2: ", generic_model.encode(req_text).shape)
print("  code_search_net:  ", code_model.encode(req_text).shape)

def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

print()
print("Discrimination test (we WANT a big gap between related and unrelated):")
for name, model in [("all-MiniLM-L6-v2", generic_model), ("code_search_net", code_model)]:
    r = model.encode(req_text)
    c_rel = model.encode(related_chunk)
    c_unrel = model.encode(unrelated_chunk)
    sim_rel = cos(r, c_rel)
    sim_unrel = cos(r, c_unrel)
    print(f"  {name}:")
    print(f"    sim(req, RELATED chunk)   = {sim_rel:.4f}")
    print(f"    sim(req, UNRELATED chunk) = {sim_unrel:.4f}")
    print(f"    gap (bigger = better discrimination) = {sim_rel - sim_unrel:.4f}")
