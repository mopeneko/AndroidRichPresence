#!/usr/bin/env python3
"""Check a signed release APK before it is published as an immutable release.

Verifies the signature, prints the certificate and asset digest, and confirms
the native bridge is packaged. Run it on the APK you intend to upload.
"""
import argparse
import hashlib
import subprocess
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apksigner", default="apksigner")
    args = parser.parse_args()

    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    print(f"apk: {args.apk.name} ({args.apk.stat().st_size} bytes)")
    print(f"sha256: {digest}")

    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
    required = [
        "classes.dex",
        "lib/arm64-v8a/libpresence_bridge.so",
        "lib/arm64-v8a/libdiscord_partner_sdk.so",
    ]
    missing = [name for name in required if name not in names]
    assert not missing, f"missing from the APK: {missing}"
    print(f"natives: ok ({sum(n.startswith('lib/') for n in names)} libraries)")

    result = subprocess.run(
        [args.apksigner, "verify", "--print-certs", str(args.apk)],
        capture_output=True,
        text=True,
    )
    print(result.stdout.strip() or result.stderr.strip())
    assert result.returncode == 0, "apksigner rejected the APK"


if __name__ == "__main__":
    main()
