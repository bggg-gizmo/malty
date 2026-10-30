#!/usr/bin/env python3
"""Source-linked acceptance tests for v0.7; runtime tests are deliberately not conflated with these tests."""
import hashlib,re,unittest
from pathlib import Path
R=Path(__file__).resolve().parent.parent
S=R/'android/app/src/main/java/org/canceledagent/app'
read=lambda f:(S/f).read_text()
class SourceParity(unittest.TestCase):
    def test_complete_capability_document_unchanged(self):
        upstream=(R/'source-reference/src/bridge-capability.md').read_bytes()
        packaged=(R/'android/app/src/main/assets/bridge-capability.md').read_bytes()
        self.assertEqual(packaged,upstream)
        self.assertGreater(len(packaged),20000)
        self.assertIn(b'bridge-capability/16',packaged)
    def test_capability_endpoint_serves_full_asset(self):
        server=read('EmbeddedServer.java')
        self.assertIn('context.getAssets().open("bridge-capability.md")',server)
        self.assertIn('"capability",doc',server)
        self.assertIn('"sha256",LocalCore.digest(doc)',server)
    def test_independent_isolated_android_db(self):
        self.assertIn('LocalCore(Context ctx,String name)',read('LocalCore.java'))
        self.assertIn('EmbeddedServer(Context c,LocalCore isolated,int port)',read('EmbeddedServer.java'))
        suite=read('DeviceIntegrationTests.java')
        for target in ['server.start(false)','websocket()', 'labReveal', 'oracleCompare', 'db.claim(', 'db.fleet(', 'db.vote(', 'db.saveRoom(', 'http("POST"','ctx.deleteDatabase(DB_NAME)']:
            self.assertIn(target,suite)
    def test_paid_anchors_are_opted_in_and_governed(self):
        bridge=read('EmbeddedServer.java');api=read('MoltbookEngine.java');ui=read('WorkflowActivity.java')
        self.assertIn('moltbook.dispatch(p)',bridge)
        for snippet in ['anchor-explicit-optIn-required','canSpend()','chargePaid()','doNotBlindRetry','anchorReceipt']:
            self.assertIn(snippet,api)
        self.assertIn('Dispatch locally (free)',ui)
        self.assertIn('Dispatch + verified Moltbook anchor (paid)',ui)
    def test_canary_and_responder_stats_are_durable(self):
        api=read('MoltbookEngine.java')
        for target in ['expect-too-long','canaryMapped','canaryUnmapped','responder-stats','recordResponder(','averageLatencyMs']:
            self.assertIn(target,api)
    def test_device_live_checks_are_read_only(self):
        main=read('MainActivity.java')
        self.assertIn('Run authenticated live-service READ-ONLY checks',main)
        self.assertIn('remoteWrites",0',main)
        for endpoint in ['/agents/status','/agents/me','/home','/feed?sort=new&limit=5','/submolts']:
            self.assertIn(endpoint,main)
    def test_no_hosted_generator_webview_dependency(self):
        # The required keyless AI and character-profile integrations may contact Perchance,
        # but the application UI and local editing MUST NOT be the hosted generator.
        ui='\n'.join(read(f) for f in ['MainActivity.java','EditorActivity.java','CanvasEditorActivity.java'])
        self.assertNotIn('https://perchance.org/canceled-perchance-agent',ui)
        self.assertNotIn('https://null.perchance.org',ui)
        self.assertIn('PerchanceInference.generate(context', read('EmbeddedServer.java'))
if __name__=='__main__':unittest.main(verbosity=2)
