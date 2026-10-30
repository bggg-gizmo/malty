#!/usr/bin/env python3
"""Static Android source acceptance gates; not a substitute for device runtime tests."""
import json,re,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
SRC=ROOT/'android/app/src/main/java/org/canceledagent/app'
read=lambda name:(SRC/name).read_text()
class EmbeddedContract(unittest.TestCase):
    def test_service_bundled(self):
        manifest=(ROOT/'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:name=".EmbeddedService"',manifest)
        self.assertIn('android:name=".BootReceiver"',manifest)
        self.assertIn('android.permission.FOREGROUND_SERVICE',manifest)
        self.assertIn('android.permission.RECEIVE_BOOT_COMPLETED',manifest)
    def test_autostart_on_launch(self):
        self.assertIn('EmbeddedService.launch(this)',read('MainActivity.java'))
        self.assertIn('startForeground(',read('EmbeddedService.java'))
        self.assertIn('new EmbeddedServer(this)',read('EmbeddedService.java'))
    def test_api_without_python(self):
        server=read('EmbeddedServer.java')
        for endpoint in ['/api/rooms','/api/votes','/ws','/api/workflows/','/api/mesh/','/v1/chat/completions','/v1/messages','/api/bridge/relay']:
            self.assertIn(endpoint,server,endpoint)
        self.assertIn('new LocalCore(context)',server)
        self.assertIn('new ServerSocket()',server)
    def test_loopback_and_opt_in_lan(self):
        self.assertIn('lan?"0.0.0.0":"127.0.0.1"',read('EmbeddedServer.java'))
        self.assertIn('RuntimeSecrets.ensure(context)',read('EmbeddedServer.java'))
        self.assertIn('Copy local service access token',read('MainActivity.java'))
    def test_blueprint_and_touch(self):
        bp=read('BlueprintProtocol.java');tb=read('TouchBridgeController.java')
        for term in ['CREATE TABLE protocols','CREATE TABLE checks','sha256','verify','audit','adopt']:
            self.assertIn(term,bp)
        for term in ['importProfile','exportProfile','dispatchMouse','dispatchKey','installBridge','getGamepads','gamepadconnected']:
            self.assertIn(term,tb)
    def test_embedded_workflows(self):
        server=read('EmbeddedServer.java')
        for feature in ['lab/probes/commit','lab/oracle/','lab/blockers','market/billboards','webmesh/deeds/scores','radio/dial','moltbook/ask','autonomy/control','team/']:
            self.assertIn(feature,server)
    def test_blueprint_checks_executable(self):
        bp=read('BlueprintAcceptance.java')
        for check in ['canceled-agent-blueprint/1','sha256','contains','not_contains','html_tag','min_revision','max_length','equals','checks.length()==0','projectId']:
            self.assertIn(check,bp)
        self.assertIn('BlueprintAcceptance.evaluate(',read('BlueprintProtocol.java'))
        self.assertIn('BlueprintAcceptance.baseline(',read('BlueprintActivity.java'))
    def test_touch_gamepad_and_pointer_compatibility(self):
        touch=read('TouchBridgeController.java')
        for condition in ['gamepadconnected','getGamepads','dispatchMouse','PointerEvent','selfTest']:
            self.assertIn(condition,touch)
    def test_bridge_cached_reply_no_duplicate_delivery(self):
        server=read('EmbeddedServer.java');client=read('MoltbookEngine.java')
        self.assertIn('moltbook-team',server)
        self.assertIn('cached',server)
        self.assertIn('cached',client)
        self.assertIn('"spend",0',client)
    def test_paid_publish_guard_and_verify_optional(self):
        molt=read('MoltbookEngine.java')
        self.assertIn('public synchronized JSONObject publish',molt)
        self.assertIn('manual-publish-approval-required',molt)
        self.assertIn('chargePaid()',molt)
        self.assertIn('verification_required',molt)
        self.assertIn('"parent_id",parentId',molt)
        self.assertIn('doNotBlindRetry',molt)
    def test_autofold_wire_and_duplicate_attempt_protection(self):
        cron=read('AppAutomation.java');wire=read('WireProtocol.java')
        self.assertIn('WireProtocol.fold(',cron)
        self.assertIn('attemptedAt',cron)
        self.assertIn('draft-already-applied-or-attempted',cron)
        self.assertIn('document-changed-during-fold',cron)
        self.assertIn('WIRE v',wire)
    def test_automated_replies_addressed_only(self):
        cron=read('AppAutomation.java')
        self.assertIn('flattenComments(',cron)
        self.assertIn('boolean addressed=',cron)
        self.assertIn('ownIds.contains(parent)',cron)
        self.assertIn('auto-reply-cooldown-',cron)
        self.assertIn('molt.verifiedComment(post,text,newest.optString("id"))',cron)
    def test_market_cap_and_radio_defaults(self):
        cron=read('AppAutomation.java');radio=read('LocalCore.java')
        self.assertIn('sessionTaglines>=6',cron)
        self.assertIn('now-lastTaglineAt<600000L',cron)
        self.assertIn('visibleAt',cron)
        for station in ['Drone Zone','Deep Space One','Groove Salad']:
            self.assertIn(station,radio)
    def test_live_local_route_diagnostics(self):
        main=read('MainActivity.java')
        self.assertIn('read-only on-device integration diagnostics',main)
        self.assertIn('/api/workflows/bridge/status',main)
        self.assertIn('/api/workflows/lab/oracles',main)
    def test_original_plugins_preserved(self):
        registry=json.loads((ROOT/'source-audit/PLUGIN_REPLACEMENTS.json').read_text())
        importSource=(ROOT/'source-reference/main.pjs').read_text()
        original=set(re.findall(r'\{import:([a-zA-Z0-9-]+)\}',importSource))
        self.assertEqual(original,set(registry['plugins']))
        for plugin,entry in registry['plugins'].items():
            self.assertTrue(entry['replacement'],plugin)
            for path in entry['replacement']:self.assertTrue((ROOT/path).is_file(),path)
if __name__=='__main__':unittest.main(verbosity=2)
