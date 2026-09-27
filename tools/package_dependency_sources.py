#!/usr/bin/env python3
"""Download matching release dependency source archives from their official Maven repositories."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
from pathlib import Path
import urllib.error
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("resolved_artifacts", type=Path, help="exportReleaseDependencies JSON")
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)

def download(artifact):
    group, name, version = (artifact[key] for key in ("group", "name", "version"))
    coordinate = f"{group}:{name}:{version}"
    with zipfile.ZipFile(artifact["file"]) as binary:
        names = binary.namelist()
        if "classes.jar" in names:
            with zipfile.ZipFile(io.BytesIO(binary.read("classes.jar"))) as classes:
                code_count = sum(n.endswith(".class") for n in classes.namelist())
        else:
            code_count = sum(n.endswith(".class") for n in names)
    repo = "https://dl.google.com/dl/android/maven2" if group.startswith("androidx.") else "https://repo.maven.apache.org/maven2"
    filename = f"{name}-{version}-sources.jar"
    url = f"{repo}/{group.replace('.', '/')}/{name}/{version}/{filename}"
    path = args.output / group / filename
    result = {"coordinate": coordinate, "binaryClassCount": code_count, "sourceUrl": url}
    try:
        if path.exists():
            data = path.read_bytes()
        else:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(url, timeout=45) as response:
                data = response.read()
        with zipfile.ZipFile(io.BytesIO(data)) as source:
            if not source.namelist():
                raise ValueError("Empty source archive")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        result.update(path=str(path.relative_to(args.output)), sha256=hashlib.sha256(data).hexdigest(), status="downloaded")
    except urllib.error.HTTPError as error:
        if error.code == 404 and code_count == 0:
            result.update(status="no executable classes; metadata-only artifact")
        else:
            result.update(status="failed", error=f"HTTP {error.code}")
    except Exception as error:
        result.update(status="failed", error=type(error).__name__ + ": " + str(error))
    return result

artifacts = json.loads(args.resolved_artifacts.read_text())
with ThreadPoolExecutor(max_workers=6) as pool:
    results = list(pool.map(download, artifacts))
(args.output / "sources-manifest.json").write_text(json.dumps(results, indent=2) + "\n")
failed = [row for row in results if row["status"] == "failed"]
print(f"Source archives: {sum(row['status'] == 'downloaded' for row in results)}; failures: {len(failed)}")
for row in failed:
    print(row["coordinate"], row["error"])
raise SystemExit(1 if failed else 0)
