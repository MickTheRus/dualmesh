#!/usr/bin/env python3
"""Sign and verify a CI-built APK using production credentials supplied through the environment."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE_SHA256 = "2fd539c5fa338ba11ded59ac9358325ac99bf08949a31bb3545cc2532b61a8ae"


def version():
    config = (ROOT / "app/build.gradle.kts").read_text()
    name = re.search(r'^\s*versionName = "(\d+\.\d+\.\d+)"\s*$', config, re.M)
    code = re.search(r'^\s*versionCode = (\d+)\s*$', config, re.M)
    if not name or not code:
        raise ValueError("Expected a numeric release version and version code")
    return name[1], int(code[1])


def run(*args, env=None):
    return subprocess.check_output(list(map(str, args)), text=True, env=env)


def prepare(apk, tools, output):
    name, code = version()
    # Reject a stale, mismatched, or debuggable artifact before handling private keys.
    badging = run(tools / "aapt2", "dump", "badging", apk)
    if f"name='io.dualmesh.app' versionCode='{code}' versionName='{name}'" not in badging:
        raise ValueError("APK package/version does not match the checked-out release source")
    if "application-debuggable" in badging:
        raise ValueError("Production APK must not be debuggable")
    with zipfile.ZipFile(apk) as archive:
        if any(b"Lio/dualmesh/app/screenshots/ScreenshotActivity;" in archive.read(n)
               for n in archive.namelist() if n.endswith(".dex")):
            raise ValueError("Screenshot fixtures must not ship in production")
    output.mkdir(parents=True, exist_ok=True)
    signed = output / f"dualmesh-{name}.apk"
    certificate = output / "dualmesh-production-certificate.pem"
    fingerprint_file = output / "SIGNING-CERTIFICATE.txt"
    password = os.environ.get("ANDROID_RELEASE_KEYSTORE_PASSWORD")
    encoded_key = os.environ.get("ANDROID_RELEASE_KEYSTORE_BASE64")
    if not password or not encoded_key:
        raise ValueError("Production keystore and password secrets are required; debug signing is never used")
    child_env = os.environ.copy()
    child_env.pop("ANDROID_RELEASE_KEYSTORE_BASE64", None)
    with tempfile.TemporaryDirectory(prefix="dualmesh-signing-") as temporary:
        keystore = Path(temporary) / "production.jks"
        with keystore.open("xb") as private_file:
            os.chmod(keystore, 0o600)
            private_file.write(base64.b64decode("".join(encoded_key.split()), validate=True))
        run("keytool", "-exportcert", "-rfc", "-keystore", keystore, "-alias", "dualmesh",
            "-storepass:env", "ANDROID_RELEASE_KEYSTORE_PASSWORD", "-file", certificate, env=child_env)
        fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()
        if fingerprint != CERTIFICATE_SHA256:
            raise ValueError("Keystore certificate does not match the pinned production certificate")
        run(tools / "zipalign", "-P", "16", "-f", "4", apk, signed)
        run("java", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", keystore, "--ks-key-alias", "dualmesh",
            "--ks-pass", "env:ANDROID_RELEASE_KEYSTORE_PASSWORD",
            "--key-pass", "env:ANDROID_RELEASE_KEYSTORE_PASSWORD", signed, env=child_env)
    verification = run("java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", signed)
    actual = next(line.split("digest: ", 1)[1] for line in verification.splitlines()
                  if "certificate SHA-256 digest:" in line)
    if actual != CERTIFICATE_SHA256:
        raise ValueError("Signed APK certificate does not match the published production certificate")
    run(tools / "zipalign", "-c", "-P", "16", "4", signed)
    formatted = ":".join(fingerprint[i:i + 2].upper() for i in range(0, len(fingerprint), 2))
    fingerprint_file.write_text(f"DualMesh production signing certificate\nSHA256: {formatted}\n")
    files = [signed, certificate, fingerprint_file]
    (output / "SHA256SUMS.txt").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files))
    print(f"Verified production APK {name} (build {code}), signature, certificate, alignment, and checksums")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-only", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.version_only:
        print(version()[0])
    else:
        if not all((args.apk, args.build_tools, args.output)):
            parser.error("--apk, --build-tools and --output are required")
        prepare(args.apk, args.build_tools, args.output)
