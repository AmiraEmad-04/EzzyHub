"""
Quick diagnostic: zips ONE java file and hits the live /ast-chunk-zip
endpoint to print the actual chunk_text being produced right now.

Usage:
  python check_chunks.py <path_to_one_java_file> [microservice_url]
"""
import sys, zipfile, io, json, requests

java_path = sys.argv[1]
url = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8000"

buf = io.BytesIO()
with zipfile.ZipFile(buf, "w") as zf:
    zf.write(java_path, java_path.split("/")[-1])
buf.seek(0)

resp = requests.post(f"{url}/ast-chunk-zip",
                      files={"file": ("test.zip", buf, "application/zip")})
print("status:", resp.status_code)
data = resp.json()
for chunk in data.get("chunks", []):
    print("-" * 60)
    print("type:", chunk.get("chunk_type"), "| method:", chunk.get("method_name"))
    print("text:", chunk.get("chunk_text"))
