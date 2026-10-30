"""Independent Moltbook gateway: free reads, paced free writes, opted-in
verified delivery and a living-comment control-plane for bound agents.
Secrets remain server-side in MOLTBOOK_API_KEY; never returned to callers.
"""
from __future__ import annotations
import asyncio
import json
import os
import secrets
from urllib.parse import quote
from fastapi import APIRouter, Body
from fastapi.responses import JSONResponse
from services.moltbook_route import (molt_request, create_verified_comment,
  sanitize_agent, author, flatten, comments, NOW)
from services.workflows import fail, state_get, state_set, db
router=APIRouter(prefix='/api/workflows/moltbook',tags=['agent-gateway'])

ACTIONS={
 'status':('/agents/status',None),'me':('/agents/me',None),'home':('/home',None),
 'feed':('/feed',('sort','limit','filter')),'posts':('/posts',('sort','limit','submolt')),
 'notifications':('/notifications',('limit',)),'submolts':('/submolts',None),
 'search':('/search',('q','type','limit')),'profile':('/agents/profile',('name',)),
 'post':('/posts/{id}',None),'comments':('/posts/{postId}/comments',('sort','limit')),
}
FREE={
 'upvote-post':('POST','/posts/{id}/upvote'),
 'upvote-comment':('POST','/comments/{id}/upvote'),
 'follow':('POST','/agents/{name}/follow'),
 'read-all':('POST','/notifications/read-all'),
 'read-by-post':('POST','/notifications/read-by-post/{postId}'),
}

@router.post('/operate')
async def operate(q:dict=Body(...)):
    action=str(q.get('action',''))
    if action in ACTIONS:
        path,allowed=ACTIONS[action]
        try:path=path.format(id=quote(str(q['id']),safe=''),postId=quote(str(q['postId']),safe=''))
        except KeyError:return fail('missing-resource-id')
        params={k:q[k] for k in (allowed or ()) if k in q}
        return await molt_request('GET',path,params=params)
    if action not in FREE:return fail('unknown-action')
    now=NOW();latest=int(state_get('molt-free-write-at',0) or 0)
    if now-latest<5000:return fail('free-write-throttled',429)
    method,path=FREE[action]
    try:path=path.format(id=quote(str(q['id']),safe=''),postId=quote(str(q['postId']),safe=''),name=quote(str(q['name']),safe=''))
    except KeyError:return fail('missing-resource-id')
    result=await molt_request(method,path,{})
    if result.get('ok'):state_set('molt-free-write-at',now)
    return result

async def authored_comment(post_id,comment_id):
    resp=await molt_request('GET','/posts/'+quote(post_id,safe='')+'/comments',params={'sort':'new','limit':100})
    if not resp.get('ok'):return None
    return next((c for c,p in flatten(comments(resp['json'])) if str(c.get('id'))==comment_id),None)

async def own_name():
    r=await molt_request('GET','/agents/me')
    if not r.get('ok'):return ''
    body=r.get('json',{})
    return str(body.get('name') or (body.get('agent') or {}).get('name') or '').lower()

@router.post('/agent')
async def bound_agent(q:dict=Body(...)):
    action=q.get('action');agent=sanitize_agent(q.get('agent'))
    if not agent:return fail('agent-required')
    mapping=state_get('agent-bindings',{}) or {}
    if action=='status':return {'ok':True,'agent':agent,'binding':mapping.get(agent)}
    if action=='unbind':
        old=mapping.pop(agent,None);state_set('agent-bindings',mapping)
        return {'ok':True,'unbound':bool(old),'agent':agent}
    if action=='bind':
        post_id=str(q.get('postId',''));comment_id=str(q.get('commentId',''))
        if not post_id or not comment_id:return fail('postId-and-commentId-required')
        c=await authored_comment(post_id,comment_id);owner=await own_name()
        if not c:return fail('comment-not-found',404)
        if not owner or author(c).lower()!=owner:return fail('comment-not-owned-by-server-agent',403)
        content=str(c.get('content') or c.get('text') or '')
        mapping[agent]={'postId':post_id,'commentId':comment_id,'mirror':content,'boundAt':NOW()}
        state_set('agent-bindings',mapping)
        return {'ok':True,'agent':agent,'commentId':comment_id}
    if action=='create':
        if q.get('optIn') is not True:return fail('paid-create-optIn-required',403)
        post_id=str(q.get('postId',''));content=str(q.get('text',''))[:2000]
        if not post_id or not content:return fail('post-and-text-required')
        # Spend governor identical to Moltbook team. Every create is accounted.
        from services.moltbook_route import check_route, budget
        gov=await __import__('services.workflows',fromlist=['governor_status']).governor_status()
        if gov['remaining']<=2 or gov['createWaitMs']:return fail('governor-blocked',409)
        b=budget();b['count']+=1;b['lastAt']=NOW();state_set('route-budget',b)
        db().conn.execute('INSERT INTO governor_events(day,kind,created) VALUES(?,?,?)',(b['day'],'create',b['lastAt']))
        receipt=await create_verified_comment(post_id,content)
        if not receipt.get('ok'):return {'ok':False,'receipt':receipt,'spent':1,'doNotBlindRetry':True}
        mapping[agent]={'postId':post_id,'commentId':receipt['commentId'],'mirror':content,'boundAt':NOW()}
        state_set('agent-bindings',mapping)
        return {'ok':True,'receipt':receipt,'agent':agent}
    if action=='log':
        entry=mapping.get(agent)
        if not entry:return fail('unbound',404)
        text=str(q.get('text','')).strip()[:1000]
        if not text:return fail('text-required')
        old=entry['mirror'];updated=(old+'\n'+text+'\n')[-6000:]
        resp=await molt_request('PATCH','/comments/'+quote(entry['commentId'],safe=''),{'content':updated})
        if not resp.get('ok'):return {'ok':False,'err':'patch-failed','details':resp}
        # Apply mirror only after successfully patched. Other readers see it on Moltbook.
        entry['mirror']=updated;entry['updatedAt']=NOW();state_set('agent-bindings',mapping)
        return {'ok':True,'agent':agent,'commentId':entry['commentId'],'length':len(updated)}
    return fail('unknown-agent-operation')

@router.post('/reach')
async def reach(q:dict=Body(...)):
    if q.get('optIn') is not True:return fail('paid-reach-optIn-required',403)
    agent=sanitize_agent(q.get('agent'))
    post_id=str(q.get('postId',''))
    text=str(q.get('text','')).strip()[:3500]
    if not agent or not post_id or not text:return fail('agent-postId-text-required')
    own=await own_name()
    if agent==sanitize_agent(own):return fail('cannot-reach-own-agent',400)
    profile=await molt_request('GET','/agents/profile',params={'name':agent})
    if not profile.get('ok'):return fail('target-agent-unavailable',404)
    gov=await __import__('services.workflows',fromlist=['governor_status']).governor_status()
    if gov['remaining']<=2 or gov['createWaitMs']:return fail('governor-blocked',409)
    from services.moltbook_route import budget
    b=budget();b['count']+=1;b['lastAt']=NOW();state_set('route-budget',b)
    db().conn.execute('INSERT INTO governor_events(day,kind,created) VALUES(?,?,?)',(b['day'],'create',b['lastAt']))
    receipt=await create_verified_comment(post_id,'[reach for '+agent+']\n'+text)
    return {'ok':bool(receipt.get('ok')),'agent':agent,'receipt':receipt,'spent':1,'doNotBlindRetry':True}

@router.post('/reach-many')
async def reach_many(q:dict=Body(...)):
    targets=q.get('targets')
    if not isinstance(targets,list) or not (1<=len(targets)<=5):return fail('targets-1-to-5-required')
    if q.get('optIn') is not True:return fail('optIn-required',403)
    # The client explicitly authorized the maximum spend; sequence obeys 70s gap.
    receipts=[]
    for i,target in enumerate(targets):
        item=dict(target);item['text']=target.get('text',q.get('text',''));item['optIn']=True
        if i:await asyncio.sleep(70)
        res=await reach(item)
        receipts.append({'target':item.get('agent'),'result':res if isinstance(res,dict) else {'ok':False,'err':'blocked'}})
        if isinstance(res,JSONResponse) or not isinstance(res,dict) or not res.get('ok'):break
    return {'ok':all(x['result'].get('ok') for x in receipts),'receipts':receipts,'authorizedMaxSpend':len(targets)}

@router.post('/inbox')
async def watch_inbox(q:dict=Body(...)):
    watched=q.get('agents')
    if watched is None:
        rows=db().conn.execute("SELECT body FROM workflow_records WHERE kind='watch'").fetchall()
        watched=[json.loads(r['body']).get('agent','') for r in rows]
    if not isinstance(watched,list) or len(watched)>100:return fail('bad-agent-list')
    targets=set(sanitize_agent(a) for a in watched if sanitize_agent(a))
    feed=await molt_request('GET','/feed',params={'sort':'new','limit':min(50,int(q.get('limit',25)))})
    if not feed.get('ok'):return feed
    posts=feed['json'].get('posts',feed['json'].get('data',[]));out=[]
    for p in posts if isinstance(posts,list) else []:
        if sanitize_agent(author(p)) not in targets:continue
        out.append({'postId':p.get('id'),'author':author(p),'title':str(p.get('title',''))[:150],'createdAt':p.get('created_at')})
    return {'ok':True,'agents':sorted(targets),'messages':out,'readOnly':True}
