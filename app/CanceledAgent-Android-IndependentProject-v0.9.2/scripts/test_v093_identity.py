"""v0.9.3 build metadata and non-regression identity tests."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

class BuildIdentity(unittest.TestCase):
    def test_direct(self):
        text=(ROOT/'scripts/build-direct.sh').read_text()
        for token in ('--version-code 12','--version-name 0.9.3','--min-sdk-version 26','--target-sdk-version 28'):
            self.assertIn(token,text)
    def test_gradle(self):
        text=(ROOT/'android/app/build.gradle').read_text()
        for token in ("applicationId 'org.canceledagent.app'","versionCode 12","versionName '0.9.3'","minSdk 26","targetSdk 28"):
            self.assertIn(token,text)
    def test_application_identity_and_features(self):
        manifest=(ROOT/'android/app/src/main/AndroidManifest.xml').read_text()
        for token in ('package="org.canceledagent.app"','android:name=".MainActivity"','android:name=".EmbeddedService"'):
            self.assertIn(token,manifest)
        source=ROOT/'android/app/src/main/java/org/canceledagent/app'
        for name in ('MainActivity','EmbeddedServer','OutboundBridge','BridgeRequestSecurity','BridgeReplayWindow','BridgeTransportSecurity','AgentRuntime','MoltbookEngine','CanvasEditorActivity','WorkflowActivity'):
            self.assertTrue((source/(name+'.java')).is_file(),name)

if __name__=='__main__':
    unittest.main(verbosity=2)
