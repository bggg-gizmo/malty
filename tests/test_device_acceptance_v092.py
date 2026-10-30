#!/usr/bin/env python3
"""Offline, host-executable regression suite for real-device acceptance tooling."""
from __future__ import annotations

from argparse import Namespace
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('malty_device_acceptance_v092', SCRIPTS / 'device_acceptance_v092.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class AcceptanceTests(unittest.TestCase):
    def test_one_online_device(self):
        self.assertEqual(m.choose_device('List of devices attached\nABC device product:pixel\n', None), 'ABC')

    def test_no_device_rejected(self):
        with self.assertRaisesRegex(m.AcceptanceError, 'Exactly one'):
            m.choose_device('List of devices attached\n', None)

    def test_multiple_devices_rejected(self):
        with self.assertRaisesRegex(m.AcceptanceError, 'Exactly one'):
            m.choose_device('A device\nB device\n', None)

    def test_explicit_device_selection(self):
        self.assertEqual(m.choose_device('A device\nB device\n', 'B'), 'B')

    def test_unauthorized_device_rejected(self):
        with self.assertRaisesRegex(m.AcceptanceError, 'not authorized'):
            m.choose_device('A unauthorized\nB device\n', 'A')

    def test_offline_device_rejected(self):
        with self.assertRaisesRegex(m.AcceptanceError, 'not authorized'):
            m.choose_device('A offline\n', 'A')

    def test_missing_selected_device_rejected(self):
        with self.assertRaisesRegex(m.AcceptanceError, 'not connected'):
            m.choose_device('A device\n', 'B')

    def test_bounds(self):
        self.assertEqual(m.bounds('[30,50][90,120]'), (30, 50, 90, 120))
        self.assertIsNone(m.bounds('[30,50][30,120]'))
        self.assertIsNone(m.bounds('30,50,90,120'))

    def test_find_tab_only_clickable_native_button(self):
        xml = '''<hierarchy><node class="android.widget.TextView" text="Studio" clickable="true" bounds="[0,0][20,20]"/>
        <node class="android.widget.Button" text="Studio" clickable="false" bounds="[20,0][50,20]"/>
        <node class="android.widget.Button" text="Studio" clickable="true" bounds="[40,50][100,90]"/></hierarchy>'''
        self.assertEqual(m.find_tab(m.parse_tree(xml), 'Studio'), (70, 70))
        self.assertIsNone(m.find_tab(m.parse_tree(xml), 'Generate'))

    def test_active_tab_requires_status_label(self):
        tree = m.parse_tree('<hierarchy><node class="android.widget.TextView" text="Ready · Studio"/></hierarchy>')
        self.assertTrue(m.active_tab(tree, 'Studio'))
        self.assertFalse(m.active_tab(tree, 'Home'))

    def test_navigation_scroll_bounds(self):
        tree = m.parse_tree('<hierarchy><node class="android.widget.HorizontalScrollView" bounds="[0,75][480,155]"/></hierarchy>')
        self.assertEqual(m.nav_scroll_bounds(tree), (0, 75, 480, 155))

    def test_invalid_xml_rejected(self):
        with self.assertRaises(m.AcceptanceError):
            m.parse_tree('<hierarchy><node')

    def test_select_tab_never_clicks_unrelated_control(self):
        tree = m.parse_tree('<hierarchy><node class="android.widget.Button" text="Send post" clickable="true" bounds="[40,50][100,90]"/></hierarchy>')
        with patch.object(m, 'get_tree', return_value=tree), patch.object(m, 'adb_command') as adb:
            self.assertFalse(m.select_tab('adb', 'ABC', 'Home'))
        adb.assert_not_called()

    def test_select_tab_verifies_content_after_tap(self):
        first = m.parse_tree('<hierarchy><node class="android.widget.Button" text="Home" clickable="true" bounds="[40,50][100,90]"/></hierarchy>')
        second = m.parse_tree('<hierarchy><node class="android.widget.TextView" text="Ready · Home"/></hierarchy>')
        with patch.object(m, 'get_tree', side_effect=[first, second]), patch.object(m, 'adb_command') as adb, patch.object(m.time, 'sleep'):
            self.assertTrue(m.select_tab('adb', 'ABC', 'Home'))
            adb.assert_called_once_with('adb', 'ABC', 'shell', 'input', 'tap', '70', '70')

    def test_mismatched_page_rejected(self):
        first = m.parse_tree('<hierarchy><node class="android.widget.Button" text="Home" clickable="true" bounds="[40,50][100,90]"/></hierarchy>')
        second = m.parse_tree('<hierarchy><node class="android.widget.TextView" text="Ready · Operations"/></hierarchy>')
        with patch.object(m, 'get_tree', side_effect=[first, second]), patch.object(m, 'adb_command'), patch.object(m.time, 'sleep'):
            self.assertFalse(m.select_tab('adb', 'ABC', 'Home'))

    def test_png_magic_and_dimensions(self):
        raw = b'\x89PNG\r\n\x1a\n' + b'0000' + b'IHDR' + (480).to_bytes(4, 'big') + (960).to_bytes(4, 'big')
        self.assertTrue(m.screenshot_is_png(raw))
        self.assertFalse(m.screenshot_is_png(raw[:8]))
        self.assertFalse(m.screenshot_is_png(b'not-a-png'))

    def test_installed_version(self):
        self.assertEqual(m.installed_version('versionCode=11 minSdk=26\nversionName=0.9.2'), {'version_code': 11, 'version_name': '0.9.2'})

    def test_uninstalled_app_never_installed_implicitly(self):
        a = Namespace(adb='adb', serial=None, apksigner=None, install_update=False,
                      smoke=False, evidence_dir=None, isolated_suite=False)
        def reply(adb, serial, *args, **kwargs):
            if args == ('devices', '-l'):
                return 'List of devices attached\nABC device\n'
            if args[:2] == ('shell', 'getprop'):
                return '36\n'
            if args[:3] == ('shell', 'pm', 'list'):
                return ''
            raise AssertionError('Unexpected ADB command: ' + repr(args))
        with patch.object(m, 'adb_command', side_effect=reply), patch.object(m.shutil, 'which', return_value='/usr/bin/adb'):
            result = m.run(a)
        self.assertEqual(result['status'], 'app_not_installed')

    def test_installed_package_read_only(self):
        a = Namespace(adb='adb', serial=None, apksigner=None, install_update=False,
                      smoke=False, evidence_dir=None, isolated_suite=False)
        seen = []
        def reply(adb, serial, *args, **kwargs):
            seen.append(args)
            if args == ('devices', '-l'):
                return 'List of devices attached\nABC device\n'
            if args[:2] == ('shell', 'getprop'):
                return '36\n'
            if args[:3] == ('shell', 'pm', 'list'):
                return 'package:org.canceledagent.app\n'
            if args[:3] == ('shell', 'dumpsys', 'package'):
                return 'versionCode=11\nversionName=0.9.2'
            raise AssertionError('Unexpected command: ' + repr(args))
        with patch.object(m, 'adb_command', side_effect=reply), patch.object(m.shutil, 'which', return_value='/usr/bin/adb'):
            result = m.run(a)
        self.assertEqual(result['status'], 'installed_package_verified')
        self.assertFalse(any('install' in c or 'input' in c or 'start' in c for c in seen))

    def test_android_below_minimum_rejected(self):
        a = Namespace(adb='adb', serial=None, apksigner=None, install_update=False,
                      smoke=False, evidence_dir=None, isolated_suite=False)
        def reply(adb, serial, *args, **kwargs):
            if args == ('devices', '-l'): return 'ABC device\n'
            if args[:2] == ('shell', 'getprop'): return '25\n'
            raise AssertionError('Unexpected ADB command: ' + repr(args))
        with patch.object(m, 'adb_command', side_effect=reply), patch.object(m.shutil, 'which', return_value='/usr/bin/adb'):
            with self.assertRaisesRegex(m.AcceptanceError, 'below Malty minimum'):
                m.run(a)

    def test_builtin_isolated_suite_result(self):
        raw = ('{"schema":"canceled-agent/device-integration/v1",'
               '"isolated":true,"paidNetworkWrites":false,"passed":2,"failed":0,'
               '"tests":[{"passed":true},{"passed":true}]}')
        node = m.ET.Element('node', {'class': 'android.widget.TextView', 'text': raw})
        tree = m.ET.Element('hierarchy')
        tree.append(node)
        self.assertEqual(m.select_integration_result(tree)['passed'], 2)

    def test_isolated_suite_not_falsely_reported_without_attestation(self):
        raw = ('{"schema":"canceled-agent/device-integration/v1",'
               '"isolated":false,"paidNetworkWrites":false,"passed":0,"failed":0,"tests":[]}')
        tree = m.ET.Element('hierarchy')
        tree.append(m.ET.Element('node', {'class': 'android.widget.TextView', 'text': raw}))
        with self.assertRaisesRegex(m.AcceptanceError, 'isolation'):
            m.select_integration_result(tree)

    def test_inconsistent_suite_case_counts_rejected(self):
        raw = ('{"schema":"canceled-agent/device-integration/v1",'
               '"isolated":true,"paidNetworkWrites":false,"passed":20,"failed":0,"tests":[]}')
        tree = m.ET.Element('hierarchy')
        tree.append(m.ET.Element('node', {'class': 'android.widget.TextView', 'text': raw}))
        with self.assertRaisesRegex(m.AcceptanceError, 'inconsistent'):
            m.select_integration_result(tree)

    def test_existing_evidence_never_overwritten(self):
        a = Namespace(adb='adb', serial=None, apksigner=None, install_update=False,
                      smoke=True, evidence_dir=None, isolated_suite=False)
        def reply(adb, serial, *args, **kwargs):
            if args == ('devices', '-l'): return 'ABC device\n'
            if args[:2] == ('shell', 'getprop'): return '36\n'
            if args[:3] == ('shell', 'pm', 'list'): return 'package:org.canceledagent.app\n'
            if args[:3] == ('shell', 'dumpsys', 'package'): return 'versionCode=11\nversionName=0.9.2'
            raise AssertionError('unexpected ' + repr(args))
        with tempfile.TemporaryDirectory() as t:
            output = Path(t)
            (output / '01-home.png').write_bytes(b'existing')
            a.evidence_dir = output
            with patch.object(m, 'adb_command', side_effect=reply), patch.object(m.shutil, 'which', return_value='/usr/bin/adb'):
                with self.assertRaisesRegex(m.AcceptanceError, 'not empty'):
                    m.run(a)
            self.assertEqual((output / '01-home.png').read_bytes(), b'existing')


if __name__ == '__main__':
    unittest.main()
