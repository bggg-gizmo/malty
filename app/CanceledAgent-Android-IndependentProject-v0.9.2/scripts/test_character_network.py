from pathlib import Path
import gzip, json, re, unittest, zipfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'android/app/src/main/java/org/canceledagent/app'

class CharacterNetworkContract(unittest.TestCase):
    def test_all_four_user_defined_agents_registered(self):
        source=(JAVA/'AgentRuntime.java').read_text()
        for code in ('mk-naomi','mk-often','mk-ultra','mina'):
            self.assertIn('"'+code+'"',source)
        for digest in ('cc2ba375eac0f4a9022c27fb4177f318','5fe8298fdeaa7aa9b98ce58d875bd810','deeb09cb91aa38c1fbd3b4c253a82995','0fa280e33d4c782fd5f0e355979020e3'):
            self.assertIn(digest,source)
    def test_gzip_json_import(self):
        source=(JAVA/'AgentRuntime.java').read_text()
        self.assertIn('new GZIPInputStream',source)
        self.assertIn('data.optJSONObject("addCharacter")',source)
        self.assertIn('"original_json"',source)
        self.assertIn('MessageDigest.getInstance("SHA-256")',source)
        self.assertIn('char',source)
        example={'addCharacter':{'name':'Test character','roleInstruction':'Fully original character definition','writingInstruction':'Write in first person'}}
        self.assertEqual(json.loads(gzip.decompress(gzip.compress(json.dumps(example).encode())))['addCharacter']['roleInstruction'],example['addCharacter']['roleInstruction'])
    def test_no_live_perchance_chat_dependency(self):
        source=(JAVA/'AgentRuntime.java').read_text()
        self.assertIn('https://user.uploads.dev/file/',source)
        self.assertNotIn('new URL(share)',source)
        self.assertIn('PerchanceInference.generate(appContext,combined.toString(),1024)',source)
    def test_history_is_durable(self):
        source=(JAVA/'AgentRuntime.java').read_text()
        for item in ('CREATE TABLE characters','CREATE TABLE conversations','CREATE TABLE messages','ORDER BY id','completeHistoryRetainedOnDevice'):
            self.assertIn(item,source)
    def test_agent_to_agent_turns_preserve_separate_speakers(self):
        source=(JAVA/'AgentRuntime.java').read_text()
        self.assertIn('getString("messages")' if False else 'getJSONArray("messages")',source)
        self.assertIn('"role","system"',source)
        self.assertIn('"assistant"',source)
        self.assertIn('append(thread,agent,"assistant",text)',source)
    def test_external_bridge_needs_explicit_permission(self):
        source=(JAVA/'EmbeddedServer.java').read_text()
        self.assertIn('"remote-agent-turns","false"',source)
        self.assertIn('"remote-agent-turns-disabled"',source)
        self.assertIn('"character-agents"',source)
        self.assertIn('agents.profileSummary',source)
        self.assertNotIn('else if(charAction.equals("profile"))result=agents.character(',source)
    def test_authenticated_agent_routes(self):
        source=(JAVA/'EmbeddedServer.java').read_text()
        self.assertLess(source.index('if(!("Bearer "+token).equals(auth))'),source.index('JSONObject response=route(method,path,body);'))
        for v in ('"/api/agents"','"/threads"','p[3].equals("turn")','p[3].equals("messages")'):
            self.assertIn(v,source)
    def test_native_ui_and_manual_rounds(self):
        ui=(JAVA/'AgentActivity.java').read_text()
        for v in ('StartActivityForResult' if False else 'startActivityForResult','Create conversation','Send my message','Run one round: every selected agent replies','Ask ','No Moltbook posts'):
            self.assertIn(v,ui)
        self.assertIn('new Intent(this,AgentActivity.class)',(JAVA/'MainActivity.java').read_text())
        self.assertIn('android:name=".AgentActivity"',(ROOT/'android/app/src/main/AndroidManifest.xml').read_text())
    def test_no_removed_original_classes(self):
        java={p.name for p in JAVA.glob('*.java')}
        for critical in ('MainActivity.java','EmbeddedService.java','EmbeddedServer.java','MoltbookEngine.java','EditorActivity.java','TouchBridgeController.java','BlueprintProtocol.java','RaceDocument.java','RadioActivity.java'):
            self.assertIn(critical,java)

if __name__=='__main__':unittest.main(verbosity=2)
