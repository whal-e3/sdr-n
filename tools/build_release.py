#!/usr/bin/env python3
"""Build with private local signing configuration; credentials never enter command arguments."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--signing-config", type=Path,
                    default=Path.home() / ".local/share/OrbitScope/signing/upload-signing.json")
parser.add_argument("gradle_args", nargs=argparse.REMAINDER)
args = parser.parse_args()
required = {"ORBIT_UPLOAD_KEYSTORE", "ORBIT_UPLOAD_KEY_ALIAS",
            "ORBIT_UPLOAD_STORE_PASSWORD", "ORBIT_UPLOAD_KEY_PASSWORD"}
config = json.loads(args.signing_config.read_text())
if set(config) != required or any(not isinstance(v, str) or not v.strip() for v in config.values()):
    raise SystemExit("Signing configuration must contain exactly the four documented ORBIT_UPLOAD_* values")
if not Path(config["ORBIT_UPLOAD_KEYSTORE"]).is_file():
    raise SystemExit("Upload keystore is missing")
env = os.environ.copy()
env.update(config)
root = Path(__file__).resolve().parents[1]
command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))]
command += args.gradle_args or [":app:bundleRelease", ":app:assembleRelease"]
raise SystemExit(subprocess.run(command, cwd=root, env=env).returncode)
