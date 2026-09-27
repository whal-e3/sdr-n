#!/usr/bin/env python3
"""Package committed app/native source plus verified matching Maven source archives."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument("dependency_sources", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
if subprocess.check_output(["git", "status", "--porcelain"], cwd=root).strip():
    raise SystemExit("Commit the reviewed release source before packaging")
if args.output.resolve().is_relative_to(root):
    raise SystemExit("Write release archives outside the checkout")
manifest = json.loads((args.dependency_sources / "sources-manifest.json").read_text())
inventory = json.loads((root / "app/src/main/assets/legal/dependencies.json").read_text())
if {row["coordinate"] for row in manifest} != {row["coordinate"] for row in inventory}:
    raise SystemExit("Dependency source inventory does not match the app inventory")
for row in manifest:
    if row["status"] == "downloaded":
        data = (args.dependency_sources / row["path"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != row["sha256"]:
            raise SystemExit("Source archive hash mismatch: " + row["coordinate"])
    elif row["status"] != "no executable classes; metadata-only artifact":
        raise SystemExit("Missing dependency source: " + row["coordinate"])
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
source = subprocess.check_output(["git", "archive", "--format=tar", "--prefix=orbitscope-source/", "HEAD"], cwd=root)
args.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(args.output, "w:gz") as archive:
    with tarfile.open(fileobj=io.BytesIO(source)) as committed:
        for member in committed:
            archive.addfile(member, committed.extractfile(member) if member.isfile() else None)
    archive.add(args.dependency_sources, arcname="orbitscope-source/dependency-sources")
    data = (json.dumps({"sourceCommit": commit, "dependencies": len(inventory)}, indent=2) + "\n").encode()
    info = tarfile.TarInfo("orbitscope-source/RELEASE_SOURCE.json")
    info.size = len(data)
    archive.addfile(info, io.BytesIO(data))
print("Source commit:", commit)
print("Archive SHA-256:", hashlib.sha256(args.output.read_bytes()).hexdigest())
