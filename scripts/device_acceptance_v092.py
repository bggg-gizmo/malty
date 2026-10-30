#!/usr/bin/env python3
"""Malty 0.9.2 Android device acceptance: deterministic, opt-in UI evidence.

Offline. Does not invoke posting, purchase or external account controls.
By default only validates the signed APK and reads device/package status.
--install-update explicitly installs with `adb install -r` (never uninstalls).
--smoke explicitly launches Malty, navigates its six *existing* main tabs via
Android UIAutomator accessibility and saves PNG screenshots locally. Screenshots
can show private user information: the tool NEVER uploads or prints their pixels
or dumps unredacted accessibility XML. Do not publish captured reports unreviewed.

Typical:
 python3 scripts/device_acceptance_v092.py --adb /path/to/adb
 python3 scripts/device_acceptance_v092.py --adb /path/to/adb --smoke
 python3 scripts/device_acceptance_v092.py --adb /path/to/adb --install-update --smoke
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from verify_v092_artifacts import ARTIFACT, ROOT, run as verify_apk

PACKAGE = 'org.canceledagent.app'
ACTIVITY = PACKAGE + '/.MainActivity'
TABS = ('Home', 'Conversations', 'Studio', 'Operations', 'Exchange', 'Settings')
APK = ROOT / 'downloads' / 'v0.9.2' / ARTIFACT
CHECKSUMS = ROOT / 'downloads' / 'v0.9.2' / 'SHA256SUMS.txt'
UI_PATH = '/data/local/tmp/malty-v092-acceptance.xml'
MIN_SDK = 26


class AcceptanceError(RuntimeError):
    pass


def adb_command(adb: str, serial: str | None, *args: str, timeout: int = 40,
                binary: bool = False) -> str | bytes:
    cmd = [adb]
    if serial:
        cmd.extend(('-s', serial))
    cmd.extend(args)
    try:
        process = subprocess.run(cmd, capture_output=True, check=False,
                                 timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise AcceptanceError('ADB command timed out: ' + ' '.join(args[:3])) from exc
    except OSError as exc:
        raise AcceptanceError('Cannot execute ADB: ' + str(exc)) from exc
    if process.returncode:
        details = process.stderr.decode('utf-8', errors='replace').strip()
        if not details:
            details = process.stdout.decode('utf-8', errors='replace').strip()
        raise AcceptanceError('ADB command failed: ' + ' '.join(args[:3]) + ': ' + details[:300])
    return process.stdout if binary else process.stdout.decode('utf-8', errors='replace')


def choose_device(lines: str, requested: str | None) -> str:
    devices = {}
    for row in lines.splitlines():
        match = re.match(r'^([^\s]+)\s+(device|unauthorized|offline|recovery|sideload|bootloader)(?:\s|$)', row)
        if match:
            devices[match.group(1)] = match.group(2)
    if requested:
        if requested not in devices:
            raise AcceptanceError('Requested device is not connected')
        if devices[requested] != 'device':
            raise AcceptanceError('Requested device is not authorized and online')
        return requested
    online = [serial for serial, state in devices.items() if state == 'device']
    if len(online) != 1:
        raise AcceptanceError(f'Exactly one authorized online device required; found {len(online)}. Use --serial to select one.')
    return online[0]


def bounds(text: str) -> tuple[int, int, int, int] | None:
    m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', text or '')
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None


def parse_tree(xml: str) -> ET.Element:
    try:
        return ET.fromstring(xml)
    except ET.ParseError as exc:
        raise AcceptanceError('Invalid UIAutomator XML; device may be locked') from exc


def find_tab(tree: ET.Element, name: str) -> tuple[int, int] | None:
    for node in tree.iter('node'):
        if node.attrib.get('text') != name or node.attrib.get('class') != 'android.widget.Button':
            continue
        if node.attrib.get('clickable') != 'true':
            continue
        b = bounds(node.attrib.get('bounds', ''))
        if b:
            return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    return None


def active_tab(tree: ET.Element, name: str) -> bool:
    return any(node.attrib.get('text') == 'Ready · ' + name for node in tree.iter('node'))


def nav_scroll_bounds(tree: ET.Element) -> tuple[int, int, int, int] | None:
    for node in tree.iter('node'):
        if node.attrib.get('class') == 'android.widget.HorizontalScrollView':
            return bounds(node.attrib.get('bounds', ''))
    return None


def screenshot_is_png(raw: bytes) -> bool:
    return len(raw) >= 24 and raw[:8] == b'\x89PNG\r\n\x1a\n' and int.from_bytes(raw[16:20], 'big') > 0 and int.from_bytes(raw[20:24], 'big') > 0


def get_tree(adb: str, serial: str) -> ET.Element:
    adb_command(adb, serial, 'shell', 'uiautomator', 'dump', UI_PATH, timeout=50)
    try:
        data = adb_command(adb, serial, 'exec-out', 'cat', UI_PATH)
        return parse_tree(data)
    finally:
        try:
            adb_command(adb, serial, 'shell', 'rm', '-f', UI_PATH)
        except AcceptanceError:
            pass


def select_tab(adb: str, serial: str, name: str) -> bool:
    """Only click a specifically named native main-navigation button."""
    for _ in range(5):
        tree = get_tree(adb, serial)
        xy = find_tab(tree, name)
        if xy is not None:
            adb_command(adb, serial, 'shell', 'input', 'tap', str(xy[0]), str(xy[1]))
            time.sleep(0.55)
            if active_tab(get_tree(adb, serial), name):
                return True
            return False
        area = nav_scroll_bounds(tree)
        if area is None:
            return False
        x1, y1, x2, y2 = area
        if x2 - x1 < 80:
            return False
        y = (y1 + y2) // 2
        adb_command(adb, serial, 'shell', 'input', 'swipe', str(x2 - 15), str(y),
                    str(x1 + 15), str(y), '360')
        time.sleep(0.4)
    return False


def select_integration_result(tree: ET.Element) -> dict | None:
    """Read only the built-in isolated-suite verdict; never store raw UI contents."""
    for node in tree.iter('node'):
        if node.attrib.get('class') != 'android.widget.TextView':
            continue
        contents = node.attrib.get('text', '')
        if '"schema":"canceled-agent/device-integration/v1"' not in contents:
            continue
        try:
            report = json.loads(contents)
        except json.JSONDecodeError:
            continue
        if report.get('schema') != 'canceled-agent/device-integration/v1':
            continue
        if report.get('isolated') is not True or report.get('paidNetworkWrites') is not False:
            raise AcceptanceError('On-device suite lacks isolation/no-paid-writes attestations')
        successes, failures, entries = report.get('passed'), report.get('failed'), report.get('tests')
        if (not isinstance(successes, int) or not isinstance(failures, int)
                or not isinstance(entries, list) or len(entries) != successes + failures):
            raise AcceptanceError('On-device suite report has inconsistent case counts')
        return {'passed': successes, 'failed': failures, 'case_count': len(entries),
                'isolated': True, 'paid_network_writes': False}
    return None


def run_isolated_integration(adb: str, serial: str) -> dict:
    if not select_tab(adb, serial, 'Home'):
        raise AcceptanceError('Cannot return to Home for isolated integration testing')
    tree = get_tree(adb, serial)
    label = 'Run isolated Android service integration suite (no paid requests)'
    target = find_tab(tree, label)
    if target is None:
        raise AcceptanceError('App does not expose the expected isolated integration test control')
    adb_command(adb, serial, 'shell', 'input', 'tap', str(target[0]), str(target[1]))
    for _ in range(12):
        time.sleep(1)
        result = select_integration_result(get_tree(adb, serial))
        if result is not None:
            return result
    raise AcceptanceError('Built-in isolated Android suite did not produce a readable result')


def installed_version(dumpsys: str) -> dict:
    m_code = re.search(r'\bversionCode=(\d+)', dumpsys)
    m_name = re.search(r'\bversionName=([^\s]+)', dumpsys)
    return {'version_code': int(m_code.group(1)) if m_code else None,
            'version_name': m_name.group(1) if m_name else None}


def run(args: argparse.Namespace) -> dict:
    apk_record = verify_apk(APK, CHECKSUMS, args.apksigner)
    executable = shutil.which(args.adb) or (str(Path(args.adb).resolve()) if Path(args.adb).is_file() else None)
    if not executable:
        raise AcceptanceError('ADB unavailable; supply --adb or install Android platform-tools')
    serial = choose_device(adb_command(executable, None, 'devices', '-l'), args.serial)
    device_key = hashlib.sha256(serial.encode()).hexdigest()[:12]
    sdk_text = adb_command(executable, serial, 'shell', 'getprop', 'ro.build.version.sdk').strip()
    if not sdk_text.isdigit():
        raise AcceptanceError('Cannot read Android SDK level')
    sdk = int(sdk_text)
    if sdk < MIN_SDK:
        raise AcceptanceError(f'Android SDK {sdk} is below Malty minimum {MIN_SDK}')
    initial = adb_command(executable, serial, 'shell', 'pm', 'list', 'packages', PACKAGE)
    present = any(line.strip() == 'package:' + PACKAGE for line in initial.splitlines())
    result = {
        'status': 'inspection_only', 'apk': {'size': apk_record['size'],
        'sha256': apk_record['sha256'], 'signature': apk_record['signature']},
        'device': {'fingerprint': device_key, 'sdk': sdk},
        'app_was_installed': present, 'installed_update': False,
        'screenshots': [], 'navigation': {}, 'device_integration_suite': 'not_executed',
        'authenticated_external_services': 'not_executed',
        'notes': 'No Moltbook/Bridge/provider authenticated operations were invoked by this test tool.'
    }
    if args.install_update:
        adb_command(executable, serial, 'install', '-r', str(APK), timeout=160)
        result['installed_update'] = True
        present = True
    if not present:
        result['status'] = 'app_not_installed'
        return result
    v = installed_version(adb_command(executable, serial, 'shell', 'dumpsys', 'package', PACKAGE, timeout=50))
    result['installed'] = v
    if v != {'version_code': 11, 'version_name': '0.9.2'}:
        raise AcceptanceError(f'Unexpected installed version: {v!r}')
    if not args.smoke:
        result['status'] = 'installed_package_verified'
        return result
    if not args.evidence_dir:
        raise AcceptanceError('--smoke requires --evidence-dir for local screenshots')
    out = args.evidence_dir.resolve()
    if out.exists() and any(out.iterdir()):
        raise AcceptanceError('Evidence directory is not empty; refusing to overwrite or mix captures')
    out.mkdir(parents=True, exist_ok=True)
    started = adb_command(executable, serial, 'shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=50)
    if 'Error:' in started or 'Exception' in started:
        raise AcceptanceError('MainActivity launch failed')
    time.sleep(1)
    all_ok = True
    for i, tab in enumerate(TABS, start=1):
        if not select_tab(executable, serial, tab):
            result['navigation'][tab] = 'not_found'
            all_ok = False
            continue
        img = adb_command(executable, serial, 'exec-out', 'screencap', '-p', binary=True, timeout=35)
        if not isinstance(img, bytes) or not screenshot_is_png(img):
            result['navigation'][tab] = 'invalid_screenshot'
            all_ok = False
            continue
        path = out / f'{i:02d}-{tab.lower()}.png'
        path.write_bytes(img)
        result['navigation'][tab] = 'captured'
        result['screenshots'].append({'name': path.name, 'size': len(img),
                                       'sha256': hashlib.sha256(img).hexdigest()})
    if args.isolated_suite:
        integration = run_isolated_integration(executable, serial)
        result['device_integration_suite'] = integration
        all_ok = all_ok and integration['failed'] == 0 and integration['case_count'] >= 20
    result['status'] = 'ui_smoke_passed' if all_ok else 'ui_smoke_incomplete'
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb', help='ADB executable or path')
    parser.add_argument('--serial', help='Exact authorized ADB device serial')
    parser.add_argument('--apksigner', help='Optional APKSigner path for v2/v3 certificate check')
    parser.add_argument('--install-update', action='store_true', help='Explicit install/update via adb install -r (never uninstall)')
    parser.add_argument('--smoke', action='store_true', help='Explicitly open Malty and capture its 6 existing navigation screens')
    parser.add_argument('--isolated-suite', action='store_true', help='Also invoke the built-in isolated SQLite/HTTP/WebSocket suite (requires --smoke)')
    parser.add_argument('--evidence-dir', type=Path, help='Local PRIVATE screenshot output folder (required for --smoke)')
    parser.add_argument('--json-output', type=Path, help='Local machine-readable summary; excludes UI text/serial/logcat')
    args = parser.parse_args()
    if args.smoke and not args.evidence_dir:
        parser.error('--smoke requires --evidence-dir')
    if args.isolated_suite and not args.smoke:
        parser.error('--isolated-suite requires --smoke')
    try:
        report = run(args)
        success = report['status'] in ('installed_package_verified', 'ui_smoke_passed')
        code = 0 if success else 2
    except (AcceptanceError, OSError, ValueError) as exc:
        report = {'status': 'blocked', 'error': str(exc)}
        code = 2
    print(json.dumps(report, indent=2))
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return code


if __name__ == '__main__':
    sys.exit(main())
