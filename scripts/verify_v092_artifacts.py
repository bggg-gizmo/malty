#!/usr/bin/env python3
"""Verify the exact Malty v0.9.2 public-testing APK without network access.

Usage: python3 scripts/verify_v092_artifacts.py
       python3 scripts/verify_v092_artifacts.py --apk /path/to/download.apk
       python3 scripts/verify_v092_artifacts.py --apk /path/to/download.apk --apksigner /path/to/apksigner

Checks size, SHA-256, Git blob SHA-1, ZIP structure and absence of private
signing keys / application databases. With --apksigner, verifies v2 and v3
signatures and the expected signing certificate. Does not claim device/runtime
or live authenticated network validation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import zipfile

REPOSITORY = 'bggg-gizmo/malty'
ARTIFACT = 'Malty-signed-v0.9.2.apk'
BYTE_COUNT = 555143
SHA256 = 'a7baf8e9dbe00dd7879da445b109cac82870b81483e9a627b5b149f448c8cb0e'
GIT_BLOB_SHA1 = '25ad469fe17db0a7cac26597d2afb8b22ae52403'
SIGNER_CERT_SHA256 = 'c2dedd7d4dbaa1b3eaf499849f2da4cc411e4e2779ce8c16642dfb5b57452010'
ROOT = Path(__file__).resolve().parent.parent
PRIVATE_SUFFIXES = ('.jks', '.keystore', '.p12', '.pfx', '.sqlite', '.sqlite3', '.db')


def digest_file(path: Path):
    sha256 = hashlib.sha256()
    git_sha1 = hashlib.sha1()
    size = path.stat().st_size
    git_sha1.update(f'blob {size}\0'.encode('ascii'))
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            sha256.update(chunk)
            git_sha1.update(chunk)
    return size, sha256.hexdigest(), git_sha1.hexdigest()


def validate_archive(path: Path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if not names:
            raise ValueError('APK ZIP is empty')
        if len(set(names)) != len(names):
            raise ValueError('APK contains duplicate entries')
        if 'AndroidManifest.xml' not in names or 'classes.dex' not in names:
            raise ValueError('APK is missing AndroidManifest.xml or classes.dex')
        for name in names:
            member = PurePosixPath(name)
            if member.is_absolute() or '..' in member.parts or '\\' in name:
                raise ValueError('Unsafe APK entry: '+name)
            lower = member.name.lower()
            if lower.endswith(PRIVATE_SUFFIXES) or lower in ('id_rsa', 'id_ed25519'):
                raise ValueError('APK contains private material or database: '+name)
        first_corrupt = archive.testzip()
        if first_corrupt is not None:
            raise ValueError('Bad ZIP entry CRC: '+first_corrupt)
    return len(names)


def validate_signature(apksigner: str, path: Path):
    cmd = [apksigner, 'verify', '--verbose', '--print-certs', str(path)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    output = proc.stdout + '\n' + proc.stderr
    if proc.returncode:
        raise ValueError('APKSigner verify failed: '+output.strip()[-1800:])
    required = ('Verified using v2 scheme (APK Signature Scheme v2): true',
                'Verified using v3 scheme (APK Signature Scheme v3): true',
                'Signer #1 certificate SHA-256 digest: '+SIGNER_CERT_SHA256)
    for marker in required:
        if marker not in output:
            raise ValueError('Missing APKSigner verification marker: '+marker)
    return {'v2': True, 'v3': True, 'signer_certificate_sha256': SIGNER_CERT_SHA256}


def run(apk: Path, checksums: Path, apksigner: str | None = None):
    if not apk.is_file() or not checksums.is_file():
        raise FileNotFoundError('APK or SHA256SUMS.txt not found')
    expected_line = f'{SHA256}  {ARTIFACT}'
    if checksums.read_text(encoding='utf-8').strip() != expected_line:
        raise ValueError('SHA256SUMS.txt disagrees with pinned v0.9.2 release')
    size, sha, git_sha = digest_file(apk)
    if (size, sha, git_sha) != (BYTE_COUNT, SHA256, GIT_BLOB_SHA1):
        raise ValueError('APK does not match official size, SHA-256 and Git blob SHA-1')
    entries = validate_archive(apk)
    result = {
        'verified': True, 'repository': REPOSITORY, 'artifact': ARTIFACT,
        'size': size, 'sha256': sha, 'git_blob_sha1': git_sha,
        'zip_entries': entries, 'signature': 'not_checked',
        'device_runtime': 'not_checked', 'authenticated_services': 'not_checked',
    }
    if apksigner:
        resolved = shutil.which(apksigner) or str(Path(apksigner).resolve())
        if not Path(resolved).is_file():
            raise FileNotFoundError('Cannot locate APKSigner: '+apksigner)
        result['signature'] = validate_signature(resolved, apk)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT/'downloads/v0.9.2'/ARTIFACT)
    parser.add_argument('--checksums', type=Path, default=ROOT/'downloads/v0.9.2/SHA256SUMS.txt')
    parser.add_argument('--apksigner', help='Explicit APKSigner executable path for v2/v3 and certificate validation')
    parser.add_argument('--json-output', type=Path, help='Write machine-readable verification report')
    args = parser.parse_args()
    try:
        result = run(args.apk, args.checksums, args.apksigner)
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.TimeoutExpired) as exc:
        result = {'verified': False, 'error': str(exc)}
        print(json.dumps(result, indent=2), file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2))
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    return 0


if __name__ == '__main__':
    sys.exit(main())
