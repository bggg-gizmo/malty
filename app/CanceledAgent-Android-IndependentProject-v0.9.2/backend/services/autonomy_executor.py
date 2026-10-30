"""Governed Moltbook autonomy tasks ported from recovered cron.js.
No writes unless owner enabled both scheduler/write flag and each task;
write actions are verified, logged and capped by UTC day.
"""
from __future__ import annotations
import json
import os
import re
from datetime import datetime,timezone
from fastapi import APIRouter,Body
from services.workflows import db,state_get,state_set,fail,NOW,governor_status
from services.inference import ask as infer_ask,config as infer_config
from services.moltbook_route import molt_request,create_verified_comment,comments,flatten,author

router=APIRouter(prefix='/api/workflows/autonomy',tags=['autonomy'])
BANNED=('guaranteed','risk-free','free ad space','click here')
WALLET=re.compile(r'0x[0-9a-fA-F]{8,}')

def day():return datetime.now(timezone.utc).strftime('%Y-%m-%d')

def gate_reply(text,recent):
    t=str(text or '').strip();low=t.lower()
    if not t:return {'ok':False,'reason':'empty'}
    if len(t)>700:return {'ok':False,'reason':'long'}
    if WALLET.search(t):return {'ok':False,'reason':'wallet'}
    if any(x in low for x in BANNED):return {'ok':False,'reason':'hype-phrase'}
    if 'http://' in low or 'https://' in low:return {'ok':False,'reason':'link'}
    if any(t==x for x in recent):return {'ok':False,'reason':'duplicate'}
    return {'ok':True}

def wire_next(line,signals):
    m=re.search(r'WIRE v(\d+)',line or '')
    new=int(m.group(1))+1 if m else 2
    line=re.sub(r'WIRE v\d+',f'WIRE v{new}',line or 'WIRE v1')
    now=datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    line=re.sub(r'\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d)?Z?',now,line)
    count=re.search(r'(builds|pricing|hello)=(\d+)',line)
    if count:line=line.replace(count.group(0),f'{count.group(1)}={int(count.group(2))+1}',1)
    if signals:line+='; '+'; '.join(signals) if 'signals:' in line else ' · signals: '+'; '.join(signals)
    return line

def repair_fold(text):
    s=str(text or '');start=s.find('{');end=s.rfind('}')
    if start<0 or end<=start:raise ValueError('no-json')
    data=json.loads(s[start:end+1]);lines=data.get('lines')
    if not isinstance(lines,list) or len(lines)>3 or any(not isinstance(x,str) or not x.strip() or len(x)>300 for x in lines):raise ValueError('invalid-lines')
    if re.search(r'https?://|0x[0-9a-fA-F]{8,}', ' '.join(lines),re.I):raise ValueError('links-wallets')
    if data.get('doc') and data['doc'] not in ('builds','pricing','hello'):raise ValueError('doc-not-allowed')
    if data.get('wire') is not None and not str(data['wire']).startswith('WIRE v'):raise ValueError('wire-format')
    return {'doc':data.get('doc'),'lines':[x.strip() for x in lines]}

async def infer(prompt,system,tokens=250):
    c=infer_config()
    for ep,model,key in ((c['endpoint'],c['model'],c['key']),(c['fallback_endpoint'],c['fallback_model'],c['fallback_key'])):
        if not ep or not model:continue
        try:
            reply=await infer_ask(ep,model,key,[{'role':'system','content':system},{'role':'user','content':prompt}],tokens,0.4)
            return {'ok':True,'text':reply['text'],'backend':model}
        except Exception:pass
    return {'ok':False,'err':'ai-provider-unavailable'}

@router.get('/docs')
async def own_docs():
    return {'ok':True,'docs':state_get('autonomy-docs',[]) or []}

@router.post('/docs')
async def configure_docs(q:dict=Body(...)):
    docs=q.get('docs')
    if not isinstance(docs,list) or len(docs)>12:return fail('docs-array-required-max12')
    out=[]
    for d in docs:
        if not isinstance(d,dict) or not d.get('postId') or not d.get('commentId'):return fail('post-comment-pair-required')
        out.append({'doc':str(d.get('doc') or 'builds')[:30],
                    'postId':str(d['postId'])[:150],'commentId':str(d['commentId'])[:150]})
    state_set('autonomy-docs',out)
    return {'ok':True,'docs':out}

async def check_owner(c):
    own=await molt_request('GET','/agents/me')
    if not own.get('ok'):return False
    info=own['json'];me=str(info.get('name') or (info.get('agent') or {}).get('name') or '').lower()
    return bool(me) and author(c).lower()==me

async def find_owned_comment(post_id,comment_id):
    response=await molt_request('GET','/posts/'+post_id+'/comments',params={'sort':'new','limit':100})
    if not response.get('ok'):return None
    c=next((c for c,parent in flatten(comments(response['json'])) if str(c.get('id'))==comment_id),None)
    return c if c and await check_owner(c) else None

def mark(kind):
    db().conn.execute('INSERT INTO governor_events(day,kind,created) VALUES(?,?,?)',(day(),kind,NOW()))

async def execute(task):
    if not os.getenv('MOLTBOOK_API_KEY'):
        return {'ok':False,'err':'moltbook-key-not-configured'}
    gov=await governor_status()
    if task=='autofold':
        if gov['folds']>=6:return {'ok':False,'err':'fold-cap'}
        docs=state_get('autonomy-docs',[]) or []
        if not docs:return {'ok':False,'err':'owned-documents-not-configured'}
        drafts=state_get('patchwatch-drafts',[]) or []
        if not drafts:return {'ok':False,'err':'no-approved-drafts'}
        draft=drafts[-1]
        try:clean=repair_fold(draft['text'])
        except (ValueError,KeyError,TypeError) as e:return {'ok':False,'err':'draft-rejected-'+str(e)}
        chosen=next((x for x in docs if x['doc']==(clean['doc'] or docs[0]['doc'])),None)
        if not chosen:return {'ok':False,'err':'draft-doc-not-configured'}
        original=await find_owned_comment(chosen['postId'],chosen['commentId'])
        if original is None:return {'ok':False,'err':'document-not-owned-or-not-visible'}
        old=str(original.get('content') or original.get('text') or '')
        lines=old.splitlines();wire=[i for i,s in enumerate(lines) if s.startswith('WIRE v')]
        if wire:lines[wire[-1]]=wire_next(lines[wire[-1]],clean['lines'])
        else:lines.append(wire_next('WIRE v1',clean['lines']))
        updated='\n'.join(lines[:-1]+clean['lines']+[lines[-1]]) if wire else '\n'.join(lines)
        result=await molt_request('PATCH','/comments/'+chosen['commentId'],{'content':updated})
        if not result.get('ok'):return {'ok':False,'err':'patch-failed','details':result}
        # Record the accepted mutation immediately; a delayed read-back must not
        # allow a second autonomous PATCH against the same document.
        mark('autofold')
        visible=await find_owned_comment(chosen['postId'],chosen['commentId'])
        readback=str((visible or {}).get('content',''))
        if updated!=readback:return {'ok':False,'err':'patch-not-yet-visible','writeAttempted':True,'retryWrite':False}
        return {'ok':True,'doc':chosen['doc'],'commentId':chosen['commentId'],'foldCount':gov['folds']+1,'verified':True}
    if task=='autoupvote':
        if gov['upvotes']>=5:return {'ok':False,'err':'upvote-cap'}
        r=await molt_request('GET','/feed',params={'sort':'new','filter':'following','limit':20})
        if not r.get('ok'):return {'ok':False,'err':'feed-unavailable'}
        posts=r['json'].get('posts',r['json'].get('data',[]));history=state_get('upvote-post-ids-'+day(),[]) or []
        for p in posts if isinstance(posts,list) else []:
            ident=str(p.get('id',''))
            if not ident or ident in history:continue
            result=await molt_request('POST','/posts/'+ident+'/upvote',{})
            if result.get('ok'):
                history.append(ident);state_set('upvote-post-ids-'+day(),history);mark('autoupvote')
                return {'ok':True,'postId':ident,'upvoteCount':gov['upvotes']+1}
        return {'ok':True,'result':'no-new-followed-posts'}
    if task=='autoreply':
        if gov['autoCreates']>=3 or gov['remaining']<=2 or gov['createWaitMs']:
            return {'ok':False,'err':'governor-blocked'}
        home=await molt_request('GET','/home')
        if not home.get('ok'):return {'ok':False,'err':'home-unavailable'}
        activities=home['json'].get('activity_on_your_posts',[])
        if not activities:return {'ok':True,'result':'quiet'}
        recent=state_get('autoreply-sent-'+day(),[]) or []
        for activity in activities[:10]:
            post_id=str(activity.get('post_id',''))
            if not post_id or any(x.get('postId')==post_id for x in recent):continue
            response=await molt_request('GET','/posts/'+post_id+'/comments',params={'sort':'new','limit':100})
            if not response.get('ok'):continue
            tree=flatten(comments(response['json']))
            owner=await molt_request('GET','/agents/me')
            if not owner.get('ok'):return {'ok':False,'err':'owner-identity-unavailable'}
            me_info=owner['json'];me=str(me_info.get('name') or (me_info.get('agent') or {}).get('name') or '').lower()
            if not me:return {'ok':False,'err':'owner-identity-unavailable'}
            fresh=[c for c,p in tree if author(c).lower()!=me]
            if not fresh:continue
            latest=fresh[-1]
            context=str(latest.get('content') or latest.get('text') or '')[:600]
            draft=await infer('Reply to this comment on my thread in one concise factual paragraph.\n'+context,
                     'You are a terse factual Moltbook agent. Under 600 characters, no URLs, wallets, hype, financial promises, or fake facts.',200)
            if not draft['ok']:return draft
            reply=draft['text'].strip()[:700]
            check=gate_reply(reply,[x['text'] for x in recent])
            if not check['ok']:return {'ok':False,'err':'reply-gated-'+check['reason'],'draft':reply}
            # Consume the create opportunity before contacting remote service; no blind retry.
            mark('create');mark('autoreply')
            receipt=await create_verified_comment(post_id,reply)
            if isinstance(receipt.get('dailyRemaining'),int):state_set('daily-remaining',receipt['dailyRemaining'])
            recent.append({'postId':post_id,'text':reply,'commentId':receipt.get('commentId'), 'verified':receipt.get('ok',False)})
            state_set('autoreply-sent-'+day(),recent)
            return {'ok':bool(receipt.get('ok')),'receipt':receipt,'postId':post_id,'posted':True,'doNotBlindRetry':True}
        return {'ok':True,'result':'no-unanswered-threads'}
    return {'ok':False,'err':'unknown-task'}

async def create_digest(task):
    if not os.getenv('MOLTBOOK_API_KEY'):return {'ok':False,'err':'moltbook-key-not-configured'}
    home=await molt_request('GET','/home')
    if not home.get('ok'):return {'ok':False,'err':'home-unavailable'}
    act=home['json'].get('activity_on_your_posts',[])
    if task=='digest':
        if not act:return {'ok':True,'summary':'No new thread activity'}
        return await infer('Summarize recent Moltbook activity:\n'+json.dumps(act[:5])[:7000],
                  'Summarize in two factual sentences, no invented numbers.',150)
    if task=='patchwatch':
        if not act:return {'ok':True,'summary':'Quiet; no draft generated'}
        ai=await infer('Draft a STRUCTURED JSON fold with keys doc (builds|pricing|hello) and lines (1-3 short factual new signals), based only on this activity:\n'+json.dumps(act[:5])[:5000],
                 'Return one JSON object, no links or wallet addresses, no fabricated facts.',350)
        if not ai['ok']:return ai
        try:repair_fold(ai['text'])
        except (ValueError,KeyError,TypeError) as exc:return {'ok':False,'err':'draft-rejected','reason':str(exc)}
        drafts=state_get('patchwatch-drafts',[]) or [];drafts.append({'at':NOW(),'text':ai['text']})
        state_set('patchwatch-drafts',drafts[-100:])
        return {'ok':True,'draftStored':True,'draft':ai['text'],'autoPosted':False}
    return {'ok':False,'err':'invalid-read-task'}

@router.get('/drafts')
async def drafts():return {'ok':True,'drafts':state_get('patchwatch-drafts',[]) or []}
