"""Standalone OpenAI/Anthropic inference bridge and team/mesh relay.
Externally routeable via HTTPS on the owner's own server; no live Perchance tab.
"""
from __future__ import annotations
import json
import asyncio
import os
import secrets
import time
from typing import Any
from fastapi import APIRouter, Body
from fastapi.responses import JSONResponse
from services.inference import ask, config
from services.workflows import (records, create_record, get_record, team_claim, team_submit,
    bridge_status, bridge_models, fail)
from services.moltbook_route import ask as molt_ask, poll as molt_poll, route as molt_route, renew as molt_renew, status as molt_status, clear_cache as molt_cache_clear, check_route as molt_check
from services.moltbook_ops import operate as molt_operate, bound_agent as molt_agent, reach as molt_reach, reach_many as molt_reach_many, watch_inbox as molt_watch_inbox
from services.mesh import (send as mesh_send, inbox as mesh_inbox, talk as mesh_talk,
    post_comment as mesh_comment, beat as mesh_beat, presence as mesh_presence)

router=APIRouter(tags=['standalone-bridge'])

@router.get('/v1/models')
async def models():
    cfg=config();ids=[]
    for ep,model in ((cfg['endpoint'],cfg['model']),(cfg['fallback_endpoint'],cfg['fallback_model'])):
        if ep and model and model not in ids:ids.append(model)
    if molt_route()['enabled']:ids.append('moltbook-team')
    return {'object':'list','data':[{'id':i,'object':'model','created':0,'owned_by':'canceled-agent-independent'} for i in ids]}

async def infer(messages:list,model:str,max_tokens:int,temperature:float):
    cfg=config();candidates=[]
    for ep,mid,key in ((cfg['endpoint'],cfg['model'],cfg['key']), (cfg['fallback_endpoint'],cfg['fallback_model'],cfg['fallback_key'])):
        if ep and mid:candidates.append((ep,mid,key))
    if model.startswith('moltbook-team'):
        cfg=molt_route()
        if not cfg['enabled']:
            return {'error':{'message':'Moltbook-team route disabled by owner','type':'route_disabled'}}
        user_text='\n'.join(m['content'] for m in messages if m['role']=='user')
        target=model.split('/',1)[1] if '/' in model else ''
        result=await molt_ask({'optIn':True,'prompt':user_text,'agent':target})
        if isinstance(result,JSONResponse):return {'error':{'message':'Moltbook route rejected request','type':'route_blocked'}}
        if not result.get('ok'):
            return {'error':{'message':str(result.get('receipt',result)),'type':'delivery_failed'}}
        job_id=result['jobId']
        if result.get('cached'):
            replies=result['replies']
            return {'id':'chatcmpl-'+secrets.token_hex(12),'object':'chat.completion','created':int(time.time()),'model':model,
                'choices':[{'index':i,'message':{'role':'assistant','content':r['text']},'finish_reason':'stop'} for i,r in enumerate(replies)],
                'moltbook':{'all':replies,'jobId':job_id,'cached':True},'usage':{'prompt_tokens':0,'completion_tokens':0,'total_tokens':0}}
        deadline=time.monotonic()+cfg['waitSec']
        response=None
        while time.monotonic()<deadline:
            await asyncio.sleep(15)
            latest=await molt_poll(job_id)
            if isinstance(latest,dict):
                response=latest
                if latest.get('done'):break
        if not response or not response.get('replies'):
            return {'error':{'message':'Moltbook agent reply not received before deadline','type':'agent_timeout','jobId':job_id}}
        replies=response['replies']
        return {'id':'chatcmpl-'+secrets.token_hex(12),'object':'chat.completion','created':int(time.time()),
            'model':model,'choices':[{'index':i,'message':{'role':'assistant','content':r['text']},'finish_reason':'stop'} for i,r in enumerate(replies)],
            'moltbook':{'all':replies,'deliveryVisible':response.get('deliveryVisible'),'jobId':job_id,
               'cached':bool(result.get('cached'))},'usage':{'prompt_tokens':0,'completion_tokens':0,'total_tokens':0}}
    if not candidates:return {'error':{'message':'No independent AI backend configured','type':'provider_not_configured'}}
    if model not in [m for e,m,k in candidates]:return {'error':{'message':'Model is not enabled','type':'model_not_found'}}
    for ep,mid,key in [p for p in candidates if p[1]==model]+[p for p in candidates if p[1]!=model]:
        try:
            r=await ask(ep,mid,key,messages,max(1,min(8192,max_tokens)),max(0,min(2,temperature)))
            return {'id':'chatcmpl-'+secrets.token_hex(12),'object':'chat.completion','created':int(time.time()),
                    'model':mid,'choices':[{'index':0,'message':{'role':'assistant','content':r['text']},'finish_reason':'stop'}],
                    'usage':{'prompt_tokens':0,'completion_tokens':0,'total_tokens':0}}
        except Exception:pass
    return {'error':{'message':'All configured inference endpoints failed','type':'upstream_failure'}}

@router.post('/v1/chat/completions')
async def chat_completion(q:dict=Body(...)):
    m=q.get('messages');model=str(q.get('model',''))
    if not isinstance(m,list) or not m or len(m)>100:return fail('invalid-messages')
    if any(not isinstance(x,dict) or x.get('role') not in ('user','system','assistant','tool') or not isinstance(x.get('content'),str) for x in m):return fail('invalid-messages')
    if sum(len(x['content']) for x in m)>100000:return fail('messages-too-large')
    r=await infer(m,model,int(q.get('max_tokens',500)),float(q.get('temperature',0.7)))
    return JSONResponse(r,status_code=502 if 'error' in r and r['error']['type'] in ('upstream_failure','agent_timeout') else 400 if 'error' in r else 200)

@router.post('/v1/messages')
async def anthropic_completion(q:dict=Body(...)):
    messages=q.get('messages',[])
    if not isinstance(messages,list):return fail('invalid-messages')
    if q.get('system'):messages=[{'role':'system','content':str(q['system'])}]+messages
    r=await infer(messages,str(q.get('model','')),int(q.get('max_tokens',500)),float(q.get('temperature',0.7)))
    if 'error' in r:return JSONResponse(r,status_code=400)
    return {'id':'msg-'+secrets.token_hex(12),'type':'message','role':'assistant','model':r['model'],
            'content':[{'type':'text','text':r['choices'][0]['message']['content']}],
            'stop_reason':'end_turn','stop_sequence':None,'usage':{'input_tokens':0,'output_tokens':0}}

@router.post('/api/bridge/relay')
async def relay(q:dict=Body(...)):
    payload=q.get('payload',q)
    if not isinstance(payload,dict):return fail('bad-payload')
    op=str(payload.get('op',''))
    if op=='team-status':result=await bridge_status()
    elif op=='team-dispatch':result=await create_record('jobs',{'task':payload.get('task',''),'slots':payload.get('slots',2),'anchor':payload.get('anchor',False)})
    elif op=='team-claim':result=await team_claim(str(payload.get('jobId','')),payload)
    elif op=='team-submit':result=await team_submit(str(payload.get('jobId','')),payload)
    elif op=='team-capability':
        result={'ok':True,'version':'independent-bridge/v1','capability':
             'REST: GET /v1/models, POST /v1/chat/completions, POST /v1/messages. '+
             'Relay: team-status, team-dispatch, team-claim, team-submit, mesh, watch, inbox, ping. '+
             'Local team jobs and mesh remain online when Android is closed if self-hosted backend is running. '+
             'Paid Moltbook agent routes require separately configured verified delivery and are never silently replaced by inference.'}
    elif op=='mesh':
        action=payload.get('action'); nick=payload.get('nick','molty')
        if action=='send':result=await mesh_send({'from':nick,'to':payload.get('to'),'text':payload.get('text')})
        elif action=='inbox':result=await mesh_inbox(str(nick))
        elif action=='talk':result=await mesh_talk(str(payload.get('topic','general')))
        elif action=='comment':result=await mesh_comment(str(payload.get('topic','general')),
                   {'nick':nick,'text':payload.get('text'),'parent':payload.get('parent')})
        elif action=='beat':result=await mesh_beat({'nick':nick,'note':payload.get('note','')})
        elif action=='presence':result=await mesh_presence()
        else:result={'ok':False,'err':'unknown-mesh-action'}
    elif op=='ping':result={'type':'touchstone-bridge.pong','ts':int(time.time()*1000),'session':'independent-server'}
    elif op=='watch':
        action=payload.get('action')
        if action=='list':result=await records('watch',200)
        elif action=='add':result=await create_record('watch',{'agent':payload.get('agent','')})
        else:result={'ok':False,'err':'unsupported-watch-action'}
    elif op=='moltbook':
        action=payload.get('action')
        if action=='route':result=await molt_status()
        elif action=='ask':result=await molt_ask(payload)
        elif action=='poll':result=await molt_poll(str(payload.get('jobId','')))
        elif action=='renew':result=await molt_renew(str(payload.get('jobId','')))
        elif action=='route-cache-clear':result=await molt_cache_clear()
        else:result=await molt_operate(payload)
    elif op=='agent':result=await molt_agent(payload)
    elif op=='reach':result=await molt_reach(payload)
    elif op=='reach-many':result=await molt_reach_many(payload)
    elif op=='inbox':result=await molt_watch_inbox(payload)
    elif op=='ask':result=await molt_ask(payload)
    elif op=='poll':result=await molt_poll(str(payload.get('jobId','')))
    elif op=='renew':result=await molt_renew(str(payload.get('jobId','')))
    else:result={'ok':False,'err':'unknown-op','supported':['team-status','team-dispatch','team-claim','team-submit','team-capability','mesh','ping','watch']}
    if isinstance(result,JSONResponse):return result
    return {'ok':True,'sessionId':'independent-server','response':result}
