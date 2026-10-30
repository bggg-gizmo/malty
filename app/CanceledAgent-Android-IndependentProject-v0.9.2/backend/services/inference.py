"""Independent inference adapter; never calls the Perchance ai-text-plugin.

Server administrator configures the provider using environment variables.  The
API key remains on the backend and is never returned to the Android client.
Supports any OpenAI-compatible provider, including a self-hosted local model.
"""
from __future__ import annotations

import os
import time
from urllib.parse import urlsplit
import httpx
from fastapi import APIRouter, Body
from fastapi.responses import JSONResponse

router = APIRouter(prefix='/api/ai', tags=['independent-inference'])

def config():
    return {'endpoint':os.getenv('AGENT_AI_ENDPOINT','').strip(),
            'model':os.getenv('AGENT_AI_MODEL','').strip(),
            'key':os.getenv('AGENT_AI_KEY','').strip(),
            'fallback_endpoint':os.getenv('AGENT_AI_FALLBACK_ENDPOINT','').strip(),
            'fallback_model':os.getenv('AGENT_AI_FALLBACK_MODEL','').strip(),
            'fallback_key':os.getenv('AGENT_AI_FALLBACK_KEY','').strip()}

def validate_url(endpoint: str) -> bool:
    u=urlsplit(endpoint)
    if u.username or u.password or not u.hostname or u.fragment:return False
    if u.scheme == 'https':return True
    # Local HTTP inference permitted for loopback/self-hosted local engines.
    return u.scheme=='http' and u.hostname in ('localhost','127.0.0.1','::1')

async def ask(endpoint: str, model: str, key: str, messages: list, max_tokens: int, temperature: float):
    if not validate_url(endpoint):
        raise ValueError('provider must use HTTPS, or HTTP on loopback')
    headers={'content-type':'application/json'}
    if key:headers['authorization']='Bearer '+key
    async with httpx.AsyncClient(timeout=60.0, follow_redirects=False) as client:
        r=await client.post(endpoint, headers=headers, json={'model':model,'messages':messages,'max_tokens':max_tokens,'temperature':temperature})
        r.raise_for_status()
        data=r.json()
        text=data.get('choices',[{}])[0].get('message',{}).get('content')
        if not isinstance(text,str) or not text.strip():raise ValueError('empty model response')
        return {'text':text,'raw':data}

@router.get('/status')
async def ai_status():
    c=config()
    return {'ok':True,'configured':bool(c['endpoint'] and c['model']), 'model':c['model'],
            'fallbackConfigured':bool(c['fallback_endpoint'] and c['fallback_model']),
            'backend':'openai-compatible-or-local', 'perchanceDependency':False}

@router.post('/generate')
async def generate(q:dict=Body(...)):
    prompt=q.get('prompt')
    if not isinstance(prompt,str) or not prompt.strip():return JSONResponse({'ok':False,'err':'empty-prompt'},status_code=400)
    if len(prompt)>100_000:return JSONResponse({'ok':False,'err':'too-big'},status_code=400)
    c=config()
    if not c['endpoint'] or not c['model']:
        return JSONResponse({'ok':False,'err':'provider-not-configured'},status_code=503)
    messages=[]
    if q.get('system'):messages.append({'role':'system','content':str(q['system'])[:20000]})
    messages.append({'role':'user','content':prompt})
    tokens=max(1,min(8192,int(q.get('maxTokens',500))))
    temp=max(0.0,min(2.0,float(q.get('temperature',0.7))))
    failures=[]
    for label,ep,model,key in [('primary',c['endpoint'],c['model'],c['key']),('fallback',c['fallback_endpoint'],c['fallback_model'],c['fallback_key'])]:
        if not ep or not model:continue
        try:
            start=time.monotonic()
            result=await ask(ep,model,key,messages,tokens,temp)
            return {'ok':True,'backend':label,'model':model,'text':result['text'],'elapsedMs':round((time.monotonic()-start)*1000)}
        except (httpx.HTTPError,ValueError,KeyError,IndexError,TypeError) as ex:
            failures.append({'backend':label,'reason':type(ex).__name__})
    return JSONResponse({'ok':False,'err':'providers-failed','failures':failures},status_code=502)
