#!/usr/bin/env python3
"""Create the persistent BYD signing key without exposing its password in process arguments."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
signing = root / ".signing"
signing.mkdir(mode=0o700, exist_ok=True)
password = signing / "password"
keystore = signing / "byd.jks"
if keystore.exists() and not password.exists():
    raise SystemExit("The existing BYD signing key needs its original .signing/password file.")
if not password.exists():
    descriptor = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(secrets.token_urlsafe(32) + "\n")
if not keystore.exists():
    java = os.environ.get("JAVA_HOME")
    keytool = str(Path(java) / "bin/keytool") if java else shutil.which("keytool")
    if not keytool:
        raise SystemExit("Set JAVA_HOME to JDK 21 before building.")
    subprocess.run([
        keytool, "-genkeypair", "-noprompt", "-storetype", "JKS",
        "-keystore", str(keystore), "-storepass:file", str(password),
        "-keypass:file", str(password), "-alias", "tailscale-byd",
        "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=Tailscale BYD, OU=Personal Build, O=Tailscale BYD",
    ], check=True)
    keystore.chmod(0o600)
print("Persistent BYD signing key ready.")
