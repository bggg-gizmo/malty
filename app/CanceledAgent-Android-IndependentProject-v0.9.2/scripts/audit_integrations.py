#!/usr/bin/env python3
"""Verify every Perchance platform import from recovered main.pjs is mapped."""
import json,re,sys
from pathlib import Path
root=Path(__file__).resolve().parent.parent
main=root/'source-reference'/'main.pjs'
registry=json.loads((root/'source-audit'/'PLUGIN_REPLACEMENTS.json').read_text())
imports=set(re.findall(r'\{import:([a-zA-Z0-9-]+)\}',main.read_text()))
mapped=set(registry['plugins'])
missing=sorted(imports-mapped)
extra=sorted(mapped-imports)
files=[]
for k,v in registry['plugins'].items():
    for filename in v['replacement']:
        if not (root/filename).is_file():files.append((k,filename))
print('Recovered imports:',len(imports))
print('Mapped replacements:',len(mapped))
print('Missing:',missing)
print('Unexpected:',extra)
print('Unresolved replacement files:',files)
assert not missing and not extra and not files
# Historical character share URLs are data, not hosted generator redirects.
# The v0.9.1+ optional first-party inference and challenge flow intentionally
# connects to text-generation.perchance.org, so do not reject it here.
# Reject direct dependence on hosted Perchance generator pages in runtime code.
allowed_character_files={'AgentRuntime.java','DeviceIntegrationTests.java'}
for path in [*(root/'android'/'app'/'src'/'main'/'java').rglob('*.java'),*(root/'backend'/'services').rglob('*.py')]:
    text=path.read_text().lower()
    if 'https://perchance.org/' in text:
        assert path.name in allowed_character_files, str(path)
        for url in re.findall(r'https://perchance\.org/[^\s"\']+',text):
            assert url.startswith('https://perchance.org/ai-character-chat?data='), (str(path),url)
print('Hosted-generator runtime dependency scan: PASS')
