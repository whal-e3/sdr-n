#!/usr/bin/env python3
"""Inspect every packaged 64-bit ELF LOAD/RELRO segment. Does not test runtime behavior."""
import argparse
import hashlib
import json
import struct
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("artifact", nargs="+")
args = parser.parse_args()
reports = []
valid = True
for path in args.artifact:
    libraries = []
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.endswith(".so"):
                continue
            data = archive.read(name)
            if data[:6] != b"\x7fELF\x02\x01":
                raise SystemExit(f"Unsupported ELF format: {name}")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            segments = []
            for index in range(count):
                kind, flags, file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
                    "<IIQQQQQQ", data, offset + index * size
                )
                if kind == 1:
                    passed = alignment >= 16384 and (address - file_offset) % 16384 == 0
                    segments.append({"kind": "LOAD", "alignment": alignment, "passed": passed})
                    valid = valid and passed
                elif kind == 0x6474E552:
                    passed = (address + memory_size) % 16384 == 0
                    segments.append({"kind": "GNU_RELRO", "endRemainder": (address + memory_size) % 16384,
                                     "passed": passed})
                    valid = valid and passed
            libraries.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(), "segments": segments})
    if not libraries:
        raise SystemExit(f"No native libraries found: {path}")
    reports.append({"artifact": path, "libraries": libraries})
print(json.dumps({"passed": valid, "runtimeTested": False, "reports": reports}, indent=2))
raise SystemExit(0 if valid else 1)
