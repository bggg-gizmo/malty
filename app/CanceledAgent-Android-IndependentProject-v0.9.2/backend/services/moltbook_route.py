"""Opt-in verified Moltbook agent delegation for the independent Bridge.
No Perchance dependency. Credentials are backend environment secrets, never API payloads.
Paid writes require enabled route + per-call optIn + governor + solver certainty.
Late replies remain collectable for 24h and receipts retain author provenance.
"""
from __future__ import annotations
import asyncio
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys
import time
from urllib.parse import urlencode
import httpx
from fastapi import APIRouter, Body
from fastapi.responses import JSONResponse
from services.workflows import fail, db, state_get, state_set, governor_status, audit, NOW

router=APIRouter(prefix='/api/workflows/moltbook',tags=['verified-delegation'])
BASE='https://www.moltbook.com/api/v1'
SOLVER=Path(__file__).resolve().parent/'solver_runtime'/'solve.mjs'
MAX_REPLIES=5

def defaults():return {'enabled':False,'postId':'','roster':[],'waitSec':120,'dailyCap':10,'quorum':1,'cacheTtlMin':0}
def route():return {**defaults(),**(state_get('molt-route',{}) or {})}
def budget():
    day=time.strftime('%Y-%m-%d',time.gmtime())
    val=state_get('route-budget',{}) or {}
    return val if val.get('day')==day else {'day':day,'count':0,'lastAt':0}

def sanitize_agent(a):return re.sub(r'[^a-z0-9-]','',str(a or '').lower())[:40]

def client():return httpx.AsyncClient(timeout=25,follow_redirects=False,trust_env=False)

async def molt_request(method,path,body=None,params=None):
    key=os.getenv('MOLTBOOK_API_KEY','').strip()
    if not key:return {'ok':False,'err':'server-moltbook-key-not-configured'}
    if not path.startswith('/') or path.startswith('//') or re.search(r'[\r\n]',path):return {'ok':False,'err':'invalid-route'}
    async with client() as c:
        try:
            resp=await c.request(method,BASE+path,params=params,json=body,headers={'Authorization':'Bearer '+key,'Accept':'application/json'})
            try:data=resp.json()
            except ValueError:data={'body':resp.text[:1000]}
            return {'ok':resp.is_success,'status':resp.status_code,'json':data,'remaining':resp.headers.get('x-ratelimit-remaining')}
        except (httpx.HTTPError,ValueError) as ex:return {'ok':False,'err':type(ex).__name__}

async def solve_challenge(challenge):
    """Executes the original tested pure solver source with no network or shell."""
    if not challenge:return {'sure':False,'unsure':True,'reason':'challenge-missing'}
    try:
        proc=await asyncio.create_subprocess_exec(os.getenv('AGENT_NODE_BIN','node'),str(SOLVER),
            stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL)
        out,_=await asyncio.wait_for(proc.communicate(json.dumps({'challenge':str(challenge)[:5000]}).encode()),timeout=5)
        if proc.returncode or len(out)>10000:return {'sure':False,'unsure':True,'reason':'solver-exit'}
        data=json.loads(out)
        return data if isinstance(data,dict) else {'sure':False,'unsure':True,'reason':'solver-shape'}
    except (OSError,asyncio.TimeoutError,ValueError):return {'sure':False,'unsure':True,'reason':'solver-runtime-unavailable'}

def flatten(nodes,parent=None):
    out=[]
    for n in nodes if isinstance(nodes,list) else []:
        if not isinstance(n,dict):continue
        out.append((n,parent))
        out+=flatten(n.get('replies',n.get('children',[])),n.get('id'))
    return out

def comments(j):
    for key in ('comments','data','items'):
        if isinstance(j.get(key),list):return j[key]
    return []

def author(c):
    a=c.get('author')
    return str(c.get('authorName') or c.get('author_name') or (a.get('name') if isinstance(a,dict) else '') or '')

def envelope(agent,c,post,text):
    unresolved=[x.strip()[:140] for x in re.findall(r'[A-Za-z][^.!?]{5,160}\?',text)[:3]]
    return {'author':agent,'commentId':c,'postId':post,'commitment':'partial','settled':False,
            'unresolved':unresolved,'deliveredAt':NOW(),'note':'relayed agent response; not a settled guarantee'}

@router.get('/route')
async def status():
    cfg=route();sp=budget();gov=await governor_status()
    return {'ok':True,'route':cfg,'spentToday':sp['count'],'cooldownMs':max(0,60000-(NOW()-sp['lastAt'])),
            'keyConfigured':bool(os.getenv('MOLTBOOK_API_KEY')),'governor':gov,
            'cacheEntries':len(state_get('route-cache',{}) or {}),'stats':state_get('route-stats',{'calls':0,'timeouts':0,'agents':{}})}

@router.post('/route')
async def configure(q:dict=Body(...)):
    old=route();cfg=dict(old)
    for k in defaults():
        if k in q:cfg[k]=q[k]
    cfg['enabled']=bool(cfg['enabled'])
    cfg['postId']=str(cfg['postId'])[:100]
    cfg['roster']=[sanitize_agent(x) for x in (cfg['roster'] if isinstance(cfg['roster'],list) else str(cfg['roster']).split(',')) if sanitize_agent(x)][:100]
    cfg['waitSec']=max(60,min(300,int(cfg['waitSec'])))
    cfg['dailyCap']=max(1,min(100,int(cfg['dailyCap'])))
    cfg['quorum']=max(1,min(5,int(cfg['quorum'])))
    cfg['cacheTtlMin']=max(0,min(43200,int(cfg['cacheTtlMin'])))
    state_set('molt-route',cfg)
    return {'ok':True,'route':cfg}

@router.post('/route/check')
async def check_route():
    cfg=route();gov=await governor_status();sp=budget()
    errs=[]
    if not cfg['enabled']:errs.append('route-disabled')
    if not cfg['postId']:errs.append('delegation-post-required')
    if not os.getenv('MOLTBOOK_API_KEY'):errs.append('server-key-required')
    if sp['count']>=cfg['dailyCap']:errs.append('daily-route-cap')
    if NOW()-sp['lastAt']<60000:errs.append('cooldown')
    if gov['remaining']<=gov['reserve']:errs.append('governor-reserve')
    if gov['createWaitMs']:errs.append('governor-create-spacing')
    return {'ok':not errs,'dry':True,'checks':errs,'postId':cfg['postId'],'quorum':cfg['quorum'],'waitSec':cfg['waitSec']}

async def create_verified_comment(post_id,contents):
    """One paid create, solve, verify and read-back without blind retries."""
    post=await molt_request('POST','/posts/'+post_id+'/comments',{'content':contents})
    if not post.get('ok'):return {'ok':False,'err':'create-failed','details':post}
    body=post['json'];c=body.get('comment') or body.get('data') or {}
    if not isinstance(c,dict) or not c.get('id'):return {'ok':False,'err':'create-receipt-shape-unknown','details':body}
    cv=c.get('verification') or body.get('verification') or {}
    if not cv:return {'ok':False,'err':'verification-receipt-missing','commentId':c['id'],'pending':True}
    parsed=await solve_challenge(cv.get('challenge_text',''))
    if not parsed.get('sure'):
        # Held challenges remain pending; no guessed answer, no extra paid posts.
        return {'ok':False,'err':'solver-unsure','reason':parsed.get('reason'),'commentId':c['id'],'pending':True}
    verified=await molt_request('POST','/verify',{'verification_code':cv.get('verification_code'),'answer':parsed['answer']})
    if not verified.get('ok'):return {'ok':False,'err':'verification-failed','commentId':c['id'],'pending':True,'verify':verified}
    return {'ok':True,'commentId':str(c['id']),'postId':post_id,
            'verification':True,'spendRecorded':True,'dailyRemaining':body.get('daily_remaining',c.get('daily_remaining'))}

@router.post('/ask')
async def ask(q:dict=Body(...)):
    cfg=route()
    if q.get('optIn') is not True:return fail('paid-delivery-requires-optIn-true',403)
    prompt=str(q.get('prompt','')).strip()
    if not prompt or len(prompt)>6000:return fail('invalid-prompt')
    target=sanitize_agent(q.get('agent'))
    if target and cfg['roster'] and target not in cfg['roster']:return fail('agent-not-allowed',403)
    cache_key=hashlib.sha256(json.dumps([prompt,target,cfg['postId'],cfg['quorum']]).encode()).hexdigest()
    cache=state_get('route-cache',{}) or {}
    old=cache.get(cache_key)
    if old and old['expiresAt']>NOW() and cfg['enabled']:
        return {'ok':True,'cached':True,'jobId':old['jobId'],'replies':old['replies'],'spend':0}
    dry=await check_route()
    if not dry['ok']:return fail('route-unavailable-'+','.join(dry['checks']),409)
    job_id='mb-'+secrets.token_hex(10)
    contents=f'[moltbook-team request {job_id} for {target or "any qualifying agent"}]\n{prompt}\n\n(reply here; your reply is relayed verbatim to a REST caller. Say your assumptions; avoid questions back.)'
    # Reserve spend BEFORE the HTTP call. A missing receipt must not re-spend.
    b=budget();b['count']+=1;b['lastAt']=NOW();state_set('route-budget',b)
    day=b['day'];db().conn.execute('INSERT INTO governor_events(day,kind,created) VALUES(?,?,?)',(day,'create',b['lastAt']))
    response=await create_verified_comment(cfg['postId'],contents)
    if isinstance(response.get('dailyRemaining'),int):state_set('daily-remaining',response['dailyRemaining'])
    entry={'id':job_id,'createdAt':NOW(),'postId':cfg['postId'],'commentId':response.get('commentId',''),
           'promptHash':cache_key,'agent':target,'roster':cfg['roster'],'quorum':cfg['quorum'],
           'expiresAt':NOW()+cfg['waitSec']*1000,'responses':[],'done':False,'deliveryVisible':None,
           'status':'pending' if response.get('ok') else response.get('err'),'receipt':response}
    state_set('route-job-'+job_id,entry)
    index=state_get('route-jobs',[]) or [];state_set('route-jobs',[job_id]+index[:199])
    if not response.get('ok'):return {'ok':False,'jobId':job_id,'receipt':response,'spend':1,'retryDelivery':False}
    return {'ok':True,'jobId':job_id,'receipt':response,'spend':1,'pollUrl':'/api/workflows/moltbook/poll/'+job_id}

@router.get('/jobs')
async def list_jobs():
    keys=state_get('route-jobs',[]) or []
    return {'ok':True,'jobs':[state_get('route-job-'+k) for k in keys if state_get('route-job-'+k)]}

@router.post('/poll/{job_id}')
async def poll(job_id:str):
    job=state_get('route-job-'+job_id)
    if not job:return fail('job-not-found',404)
    if job.get('status') not in ('pending','complete','timeout'):
        return {'ok':False,'jobId':job_id,'err':job['status'],'receipt':job.get('receipt')}
    if job.get('doneAt') and NOW()>job['doneAt']+86400000:return fail('late-collection-expired',410)
    response=await molt_request('GET','/posts/'+job['postId']+'/comments',params={'sort':'new','limit':100})
    if not response.get('ok'):return {'ok':False,'jobId':job_id,'err':'comments-unavailable','status':response.get('status')}
    flat=flatten(comments(response['json']));delivery=job.get('commentId')
    job['deliveryVisible']=any(str(c.get('id'))==delivery for c,parent in flat)
    existing={x['commentId'] for x in job['responses']}
    # Only descendants of THIS delivery qualify. No unrelated agent comments.
    descendant={delivery};hits=[]
    for c,parent in flat:
        if str(c.get('parent_id') or c.get('parentId') or parent or '') in descendant:
            descendant.add(str(c.get('id')));hits.append(c)
    for c in hits:
        ident=str(c.get('id',''));a=author(c);n=sanitize_agent(a)
        if not ident or ident in existing or not n or ident==delivery:continue
        if job.get('agent') and n!=job['agent']:continue
        if job.get('roster') and n not in job['roster']:continue
        if n==sanitize_agent(os.getenv('MOLTBOOK_AGENT_NAME','')):continue
        content=str(c.get('content') or c.get('text') or c.get('body') or '').strip()[:2000]
        if not content or content.lower()=='deleted comment':continue
        job['responses'].append({'agent':a,'commentId':ident,'text':content,'envelope':envelope(a,ident,job['postId'],content)})
        existing.add(ident)
        stats=state_get('route-stats',{'calls':0,'timeouts':0,'agents':{}})
        e=stats['agents'].setdefault(n,{'answers':0,'msTotal':0})
        e['answers']+=1;e['msTotal']+=max(0,NOW()-job['createdAt']);stats['calls']+=1
        state_set('route-stats',stats)
    if len(job['responses'])>=job['quorum']:
        job['done']=True;job['status']='complete';job['doneAt']=NOW()
    elif NOW()>=job['expiresAt']:
        job['done']=True;job['status']='timeout';job.setdefault('doneAt',NOW())
        if not job.get('timeoutRecorded'):
            stats=state_get('route-stats',{'calls':0,'timeouts':0,'agents':{}});stats['timeouts']+=1;stats['calls']+=1;state_set('route-stats',stats)
            job['timeoutRecorded']=True
    state_set('route-job-'+job_id,job)
    cfg=route()
    if job['responses'] and cfg['cacheTtlMin']:
        cache=state_get('route-cache',{}) or {};cache[job['promptHash']]={'jobId':job_id,'replies':job['responses'],'expiresAt':NOW()+cfg['cacheTtlMin']*60000}
        state_set('route-cache',cache)
    return {'ok':True,'jobId':job_id,'status':job['status'],'done':job['done'],'replies':job['responses'],
            'deliveryVisible':job['deliveryVisible'],'quorum':job['quorum'],'receipt':job.get('receipt'),
            'lateCollectUntil':job.get('doneAt',job['expiresAt'])+86400000}

@router.post('/renew/{job_id}')
async def renew(job_id:str):
    job=state_get('route-job-'+job_id)
    if not job:return fail('not-found',404)
    if job['done']:return fail('job-closed',409)
    job['expiresAt']=min(job['createdAt']+1800000,max(NOW(),job['expiresAt'])+60000)
    state_set('route-job-'+job_id,job)
    return {'ok':True,'jobId':job_id,'expiresAt':job['expiresAt'],'spend':0}

@router.post('/cache/clear')
async def clear_cache():
    cache=state_get('route-cache',{}) or {};state_set('route-cache',{})
    return {'ok':True,'removed':len(cache)}
