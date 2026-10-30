#!/usr/bin/env python3
"""Read-only, byte-preserving Perchance source mirror + integrity audit.

Python 3.10+ and curl required for network mode. Makes no authenticated requests
and never saves, modifies, or uploads code to Perchance.
"""
from __future__ import annotations

import argparse
import os
import csv
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import time
from urllib.parse import quote

GENERATOR = os.environ.get("PERCHANCE_GENERATOR", "canceled-perchance-agent")
API = "https://perchance.org/api/getGeneratorPageData"
CDN = "https://user.uploads.dev/file/"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
STATIC_IMPORT_RE = re.compile(r'''\bimport\s*(?:[\s\S]*?\s+from\s+)?\s*["'](\.{1,2}/[^"']+)["']''')
DYNAMIC_IMPORT_RE = re.compile(r'''\bimport\s*\(\s*["'](\.{1,2}/[^"']+)["']\s*\)''')
# The primary UI imports these 12 JS modules. They must be verified, not inferred.
# Plugin generators are standalone; no main-dashboard import list is presumed.
REQUIRED_MODULES = ()
SUPPORT_REFERENCES = ()


def request_bytes(url: str, post_body: bytes | None = None, timeout: int = 75) -> bytes:
    """Curl mirrors the public-access reader's request method and headers."""
    command = ["curl", "--fail-with-body", "--silent", "--show-error", "--location",
               "--connect-timeout", "20", "--max-time", str(timeout),
               "--user-agent", UA]
    if post_body is not None:
        command += ["--request", "POST", "--header", "Content-Type: application/json",
                    "--header", "Origin: https://perchance.org",
                    "--header", f"Referer: https://perchance.org/{GENERATOR}",
                    "--data-binary", "@-"]
    command.append(url)
    try:
        p = subprocess.run(command, input=post_body, stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, check=False, timeout=timeout + 10)
    except FileNotFoundError as exc:
        raise RuntimeError("curl not found on PATH") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"Request timed out: {url}") from exc
    if p.returncode != 0:
        raise RuntimeError(f"HTTP/network failure at {url}: {p.stderr.decode(errors='replace').strip()}")
    return p.stdout


def safe_manifest_path(name: str) -> pathlib.PurePosixPath:
    if not isinstance(name, str) or not name or '\\' in name or '\x00' in name:
        raise ValueError(f"Unsafe manifest path: {name!r}")
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or any(part in ('..', '.') for part in name.split('/')) or ':' in name:
        raise ValueError(f"Unsafe manifest path: {name!r}")
    if not path.parts or path.parts[0] != 'src':
        # Perchance srcManifest keys may be bare names or already prefixed src/.
        path = pathlib.PurePosixPath('src') / path
    return path


def sha256(blob: bytes) -> str:
    return hashlib.sha256(blob).hexdigest()


def save_bytes(root: pathlib.Path, name: str, data: bytes) -> pathlib.Path:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def norm_src(name: str) -> str:
    return safe_manifest_path(name).as_posix()


def normalize_import(source_file: str, target: str) -> str | None:
    """Resolve relative JS imports against containing path without OS path assumptions."""
    base = pathlib.PurePosixPath(source_file).parent
    parts: list[str] = []
    for part in (*base.parts, *target.split('/')):
        if part in ('', '.'): continue
        if part == '..':
            if not parts: return None
            parts.pop()
        else: parts.append(part)
    return '/'.join(parts)


def parse_imports(source_file: str, data: bytes) -> list[dict]:
    if not source_file.endswith(('.html', '.js', '.mjs', '.pjs')):
        return []
    src = data.decode('utf-8', errors='replace')
    found = []
    for pattern in (STATIC_IMPORT_RE, DYNAMIC_IMPORT_RE):
        # Split by statement/line first so `import` cannot span the entire file.
        for lineno, line in enumerate(src.splitlines(), 1):
            for match in pattern.finditer(line):
                found.append({'source': source_file, 'line': lineno,
                              'literal': match.group(1),
                              'resolved': normalize_import(source_file, match.group(1))})
    return sorted({(m['source'], m['line'], m['literal'], m['resolved']): m
                   for m in found}.values(), key=lambda x: (x['source'], x['line'], x['literal']))


def default_output_dir() -> pathlib.Path:
    """Keep recovered files inside this extracted package, on Android or desktop."""
    return pathlib.Path(__file__).resolve().parent / 'recovered-source'


def resolve_output_dir(value: str | None) -> pathlib.Path:
    return pathlib.Path(value).expanduser().resolve() if value else default_output_dir()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', default=None, help='Optional output directory (default: recovered-source/ beside recover.py)')
    ap.add_argument('--api-json', metavar='FILE', help='Use a previously saved full API JSON response instead of network POST')
    ap.add_argument('--local-src', metavar='DIR', help='Local source folder for offline manifest asset tests')
    ap.add_argument('--only-inventory', action='store_true', help='Skip CDN downloads and report missing assets')
    args = ap.parse_args()
    dest = resolve_output_dir(args.out)
    dest.mkdir(parents=True, exist_ok=True)

    try:
        body = (pathlib.Path(args.api_json).read_bytes() if args.api_json else
                request_bytes(API, json.dumps({'generatorName': GENERATOR}).encode('utf-8')))
        doc = json.loads(body)
        generator = doc.get('generator')
        if not isinstance(generator, dict):
            raise ValueError('API response lacks generator object. No sources recovered.')
        if not any(key in generator for key in ('modelText', 'outputTemplate', 'srcManifest')):
            raise ValueError('API generator object lacks expected source fields.')
        manifest = generator.get('srcManifest') or {}
        if not isinstance(manifest, dict):
            raise ValueError('srcManifest is not a mapping.')
    except Exception as exc:
        print('RECOVERY FAILED: ' + str(exc), file=sys.stderr)
        print('No claim of live source retrieval can be made.', file=sys.stderr)
        return 2

    save_bytes(dest, 'main.pjs', generator.get('modelText', '').encode('utf-8'))
    save_bytes(dest, 'index.html', generator.get('outputTemplate', '').encode('utf-8'))
    # This file keeps the authoritative manifest, not just guessed required entries.
    save_bytes(dest, 'src-manifest.json',
               json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8'))

    manifest_files = {}
    errors = {}
    for original, record in sorted(manifest.items()):
        try:
            path = norm_src(original)
            key = record['key']
            if not isinstance(key, str) or not key:
                raise ValueError('manifest entry has no CDN key')
            if args.local_src:
                base = pathlib.Path(args.local_src)
                blob = (base / path).read_bytes()
            elif args.only_inventory:
                errors[path] = 'not downloaded (--only-inventory)'
                continue
            else:
                blob = request_bytes(CDN + quote(key, safe=''))
            save_bytes(dest, path, blob)
            manifest_files[path] = {'original': original, 'bytes': len(blob),
                                    'sha256': sha256(blob),
                                    'manifest_size': record.get('size')}
        except Exception as exc:
            errors[original] = str(exc)
            print(f'FAILED {original}: {exc}', file=sys.stderr)

    current_files = {'index.html': (dest / 'index.html').read_bytes(),
                     'main.pjs': (dest / 'main.pjs').read_bytes()}
    for file in manifest_files:
        current_files[file] = (dest / file).read_bytes()
    refs = []
    for name, blob in sorted(current_files.items()):
        refs.extend(parse_imports(name, blob))
    for ref in refs:
        ref['present'] = ref['resolved'] in current_files
    required = {p: {'downloaded': p in manifest_files,
                    'in_manifest': p in {norm_src(k) for k in manifest}}
                for p in REQUIRED_MODULES}
    supports = {p: {'downloaded': p in manifest_files,
                    'in_manifest': p in {norm_src(k) for k in manifest}}
                for p in SUPPORT_REFERENCES}
    report = {
        'generator': GENERATOR,
        'retrieved_unix_time': int(time.time()),
        'source': 'local API JSON fixture' if args.api_json else API,
        'wrote_model': 'main.pjs', 'wrote_output': 'index.html',
        'model_sha256': sha256(current_files['main.pjs']),
        'output_sha256': sha256(current_files['index.html']),
        'manifest_entries': len(manifest), 'downloaded_entries': len(manifest_files),
        'manifest_files': manifest_files, 'failed_files': errors,
        'required_modules': required, 'support_references': supports,
        'imports': refs,
        'unresolved_imports': [x for x in refs if not x['present']],
        'complete_manifest_download': not errors and len(manifest_files) == len(manifest),
        'all_twelve_modules': all(x['downloaded'] for x in required.values()),
    }
    save_bytes(dest, 'source-audit.json',
               json.dumps(report, ensure_ascii=False, indent=2).encode('utf-8'))
    with (dest / 'imports.csv').open('w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=['source', 'line', 'literal', 'resolved', 'present'])
        writer.writeheader()
        writer.writerows(refs)
    with (dest / 'files.sha256').open('w', encoding='utf-8') as f:
        for name, blob in sorted(current_files.items()):
            f.write(f'{sha256(blob)}  {name}\n')

    print(f'Generator: {GENERATOR}')
    print(f'Output folder: {dest}')
    print(f'Manifest files downloaded: {len(manifest_files)}/{len(manifest)}')
    print(f'Required JS modules downloaded: {sum(x["downloaded"] for x in required.values())}/12')
    print(f'Unresolved source imports: {len(report["unresolved_imports"])}')
    print(f'Complete manifest: {report["complete_manifest_download"]}')
    print('Audit: ' + str(dest / 'source-audit.json'))
    return 0 if report['complete_manifest_download'] and report['all_twelve_modules'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
