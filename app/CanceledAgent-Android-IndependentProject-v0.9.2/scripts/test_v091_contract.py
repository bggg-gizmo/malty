"""Regression tests for the recovered native plug-in ports; these are NOT device-execution claims."""
from pathlib import Path
import re, subprocess, unittest, zipfile, hashlib, json
P=Path(__file__).resolve().parents[1]
JAVA=P/'android/app/src/main/java/org/canceledagent/app'
ASSETS=P/'android/app/src/main/assets'
SOURCE=P/'scripts/parity_baseline_v090.json'
class V091(unittest.TestCase):
 def test_preserve_every_original_class(self):
  old=json.loads(SOURCE.read_text())['java_classes']
  self.assertTrue(set(old).issubset({k.name for k in JAVA.glob('*.java')}))
 def test_preserve_original_declared_methods(self):
  pat=re.compile(r'\b(?:public|private|protected|static|synchronized|final|native|abstract|\s)+\s+[\w<>\[\],.?]+\s+(\w+)\s*\(')
  old=json.loads(SOURCE.read_text())['java_classes']
  for name,methods in old.items():
   current=set(pat.findall((JAVA/name).read_text()))
   self.assertTrue(set(methods).issubset(current),f'{name}: lost {set(methods)-current}')
 def test_canvas_editor_byte_identical_assets(self):
  original=P/'upstream-reference/canceled-codebase-generator-plugin/src/canvas-editor'
  for f in original.glob('*.js'):
   self.assertEqual(f.read_bytes(),(ASSETS/'canvas-editor'/f.name).read_bytes())
  self.assertIn('new CanvasEditor',(ASSETS/'canvas-editor/studio.html').read_text())
 def test_offline_editor_not_hosted(self):
  js=(JAVA/'CanvasEditorActivity.java').read_text()
  self.assertIn('shouldInterceptRequest(',js)
  self.assertIn('new WebResourceResponse',js)
  self.assertNotIn('perchance.org/',js)
  self.assertIn('projects.save(id,name,content',js)
 def test_thinking_migration_and_retention(self):
  s=(JAVA/'LocalProjects.java').read_text()
  for v in ('null,2','trace_details','onUpgrade','restoreTraceSnapshot','feedbackExport','TRACE-BACK:','detailedTrace'):
   self.assertIn(v,s)
  self.assertIn('Isolated Thinking migration',(JAVA/'DeviceIntegrationTests.java').read_text())
 def test_required_perchance_free_path_integrated(self):
  engine=(JAVA/'EmbeddedServer.java').read_text();agent=(JAVA/'AgentRuntime.java').read_text();inf=(JAVA/'PerchanceInference.java').read_text()
  self.assertIn('PerchanceInference.generate(context',engine)
  self.assertIn('PerchanceInference.generate(appContext',agent)
  self.assertIn('perchance-free',engine)
  self.assertIn('aiAllowFreeFallback',engine)
  for v in ('text-generation.perchance.org','/api/generate','perchance-verification-required','perchance-stream-incomplete','CookieManager.getInstance()','perchance-rate-limited'):
   self.assertIn(v,inf)
 def test_free_provider_never_accesses_moltbook_credentials(self):
  inf=(JAVA/'PerchanceInference.java').read_text()
  self.assertNotIn('moltbook',inf.lower().replace('moltbookcreditsspent',''))
  self.assertNotIn('aiKey',inf)
  self.assertNotIn('Authorization',inf)
 def test_donation_renderer_reproduces_upstream_defaults(self):
  data=(ASSETS/'donation-banner.js').read_text()
  old=(P/'upstream-reference/donation-link-2-ad/main.pjs').read_text()
  for name in ['paypal.com/qrcodes/p2pqrc/WGMNG3RKN97BL','https://cash.app/$falcox904','4guatesjezaC5h2niYR6ZDmZ6pQagQAAvtnwBf7NMNpK','0xFBdB59298ea0B9D867897CCEDDb0De1e2B03909c']:
   self.assertIn(name,old);self.assertIn(name,data)
  js='const fs=require("fs"),vm=require("vm");let ctx={};vm.runInNewContext(fs.readFileSync(process.argv[1],"utf8")+";globalResult=donationBanner({compact:true,dark:\'dark\'});",ctx); if(!ctx.globalResult.includes("dl2a-compact dl2a-theme-dark")||!ctx.globalResult.includes("dl2a-pp")||!ctx.globalResult.includes("dl2a-et")) process.exit(2);'
  result=subprocess.run(['node','-e',js,str(ASSETS/'donation-banner.js')],capture_output=True,text=True)
  self.assertEqual(result.returncode,0,result.stderr)
 def test_no_gradle_or_fabricated_model_results(self):
  build=(P/'scripts/build-direct.sh').read_text()
  self.assertIn('D8',build);self.assertNotIn('gradlew',build)
  for src in JAVA.glob('*.java'):
   self.assertNotIn('lorem ipsum',src.read_text().lower())
if __name__=='__main__':unittest.main(verbosity=2)
