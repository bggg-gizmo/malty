#!/usr/bin/env python3
"""Static wiring assertions. These are not substitutes for executing the embedded service on Android."""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'android/app/src/main/java/org/canceledagent/app'

class RaceIntegrationContract(unittest.TestCase):
    def test_native_ui_integrates_all_race_paths(self):
        source=(JAVA/'WorkflowActivity.java').read_text()
        for path in ['/api/workflows/bridge/race', '/api/workflows/bridge/race/send', '/collect', '/document', '/roster']:
            self.assertIn(path,source)
        self.assertIn('raceWorkflow();',source)
        self.assertIn('optIn",true',source)
    def test_backend_routes_to_persistent_engine(self):
        source=(JAVA/'EmbeddedServer.java').read_text()
        for action in ['raceCreate(data)','raceSend(data)','raceRoster(data.optString','raceCollect(', 'raceDocument(', 'raceList()']:
            self.assertIn(action,source)
    def test_paid_send_has_double_observation_retry_guard(self):
        source=(JAVA/'MoltbookEngine.java').read_text()
        self.assertIn('"race-paid-send-requires-optIn"',source)
        self.assertIn('"race-retry-requires-confirmed-nonvisibility"',source)
        self.assertIn('"missingObservations"',source)
        self.assertIn('checkedAt-previous>=10000L',source)
        self.assertIn('chargePaid();',source)
        self.assertIn('"doNotBlindRetry",true',source)
    def test_owner_and_reply_attribution_rules(self):
        source=(JAVA/'MoltbookEngine.java').read_text()
        self.assertIn('nick.equals(owner)',source)
        self.assertIn('nick.equals(t.optString("agent"))',source)
        self.assertIn('RaceDocument.combine(',source)
        self.assertIn('"commentId",cid',source)
    def test_manual_create_uses_authorized_device_governor(self):
        for name in ('MainActivity.java','WorkflowActivity.java'):
            source=(JAVA/name).read_text()
            self.assertNotRegex(source,r'\.moltbook\("POST",\s*"/posts')
            self.assertIn('"/api/workflows/moltbook/publish"',source)
    def test_independent_zero_perchance_runtime(self):
        # Character share links are inert source metadata; the app fetches gzipped
        # character JSON from user.uploads.dev once, then runs its own inference.
        java='\n'.join(p.read_text() for p in JAVA.glob('*.java') if p.name not in ('AgentRuntime.java','DeviceIntegrationTests.java'))
        self.assertNotIn('https://perchance.org/',java)
        agents=(JAVA/'AgentRuntime.java').read_text()
        self.assertIn('https://user.uploads.dev/file/',agents)
        self.assertNotIn('new URL(share)',agents)

if __name__=='__main__':unittest.main(verbosity=2)
