# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Create Paperouette's ignored persistent release key and local credentials."""

from __future__ import annotations

import argparse
import os
import secrets
import shutil
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", type=Path, default=Path("paperouette-release.jks"))
    parser.add_argument("--properties", type=Path, default=Path("keystore.properties"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.keystore.exists() or args.properties.exists():
        print("error: signing files already exist; refusing to overwrite them", file=sys.stderr)
        return 1
    keytool = shutil.which("keytool")
    if keytool is None:
        print("error: keytool is not on PATH; install a JDK (see README.md)", file=sys.stderr)
        return 1

    password = secrets.token_urlsafe(36)
    command = [
        keytool,
        "-genkeypair",
        "-keystore",
        str(args.keystore),
        "-storetype",
        "PKCS12",
        "-alias",
        "paperouette",
        "-keyalg",
        "RSA",
        "-keysize",
        "4096",
        "-validity",
        "10000",
        "-dname",
        "CN=Paperouette, OU=Private, O=Paperouette",
        "-storepass",
        password,
        "-keypass",
        password,
        "-noprompt",
    ]
    try:
        subprocess.run(command, check=True)
        args.properties.write_text(
            "\n".join(
                [
                    f"storeFile={args.keystore}",
                    f"storePassword={password}",
                    "keyAlias=paperouette",
                    f"keyPassword={password}",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        os.chmod(args.keystore, 0o600)
        os.chmod(args.properties, 0o600)
    except (OSError, subprocess.CalledProcessError) as error:
        args.keystore.unlink(missing_ok=True)
        args.properties.unlink(missing_ok=True)
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"Created {args.keystore} and {args.properties}; back up both files securely.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

