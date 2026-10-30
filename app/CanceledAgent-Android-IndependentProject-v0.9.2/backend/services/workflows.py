"""Independent workflow services: Fleet, Market, Lab, Net, Radio, Infra, Allies,
Intel, Webmesh deeds, team array, bridge, and controlled autonomy.
No Perchance calls or hosted generator dependencies. SQLite is authoritative.
Network-wide collaboration requires running this separately deployable service.
"""
from __future__ import annotations
import asyncio
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import socket
import ssl
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit
import httpx
from fastapi import APIRouter, Body
from fastapi.responses import JSONResponse
from services.inference import ask as infer_ask, config as infer_config
from services.rooms_votes.app import Store

router = APIRouter(prefix='/api/workflows', tags=['workflows'])
_store: Store | None = None
KINDS = ('ads', 'deeds', 'readers', 'bounties', 'services', 'stations', 'roster', 'fleet', 'polls', 'allies', 'infra', 'intel', 'watch', 'providers', 'jobs', 'agents', 'net-requests')
LIMITS = {'ads':500, 'deeds':500, 'readers':200, 'bounties':200, 'services':200, 'stations':300, 'roster':40, 'fleet':200, 'polls':100, 'allies':200, 'infra':60, 'intel':500, 'watch':200, 'providers':200, 'jobs':100, 'agents':100, 'net-requests':200}
REQUIRED = {'ads':('nick','url','price'), 'deeds':('nick','url','upkeep'), 'services':('name','url','kind'), 'stations':('name','url'), 'roster':('nick','role'), 'fleet':('title','brief'), 'polls':('question',), 'allies':('agent',), 'infra':('nick','url','kind'), 'jobs':('task',), 'agents':('agent',), 'readers':('nick','deedId'), 'bounties':('title',), 'intel':('title',), 'watch':('agent',), 'providers':('name','endpoint','model'), 'net-requests':('kind','description')}
AUTONOMY = {
 'feedscan':('read',3600,True),'digest':('read',3600,True),'patchwatch':('read',1800,True),
 'autofold':('write',1800,False),'autoreply':('write',1800,False),'autoupvote':('write',3600,False)
}
NOW = lambda: int(time.time()*1000)
def fail(msg,status=400):return JSONResponse({'ok':False,'err':msg},status_code=status)
def db():
    assert _store is not None
    return _store

def mount(store:Store)->APIRouter:
    global _store
    _store=store
    store.conn.executescript('''
    CREATE TABLE IF NOT EXISTS workflow_records (
      kind TEXT NOT NULL,id TEXT NOT NULL,version INTEGER NOT NULL,body TEXT NOT NULL,
      created INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(kind,id));
    CREATE INDEX IF NOT EXISTS wf_kind_updated ON workflow_records(kind,updated DESC);
    CREATE TABLE IF NOT EXISTS workflow_audit (
      id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,kind TEXT NOT NULL,
      record_id TEXT NOT NULL,operation TEXT NOT NULL,actor TEXT NOT NULL,detail TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS workflow_state (name TEXT PRIMARY KEY,value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS lab_commits (
      id TEXT PRIMARY KEY,hash TEXT NOT NULL,note TEXT NOT NULL,created INTEGER NOT NULL,
      revealed INTEGER NOT NULL DEFAULT 0,proof TEXT NOT NULL DEFAULT '');
    CREATE TABLE IF NOT EXISTS lab_verdicts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,probe_id TEXT NOT NULL,caught INTEGER NOT NULL,
      created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS lab_oracle_history (
      id INTEGER PRIMARY KEY AUTOINCREMENT,oracle_key TEXT NOT NULL,from_version INTEGER NOT NULL,
      old_contract TEXT NOT NULL,new_contract TEXT NOT NULL,reason TEXT NOT NULL,at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS lab_oracles (
      key TEXT PRIMARY KEY,hash TEXT NOT NULL,contract TEXT NOT NULL,version INTEGER NOT NULL,
      updated INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS lab_blockers (
      id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
      resolved INTEGER NOT NULL DEFAULT 0,history TEXT NOT NULL,scheduled INTEGER NOT NULL,rollovers INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS governor_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL,kind TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS autonomy_tasks (
      id TEXT PRIMARY KEY,enabled INTEGER NOT NULL,last_run INTEGER NOT NULL DEFAULT 0,
      result TEXT NOT NULL DEFAULT '',next_run INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS bridge_responses (
      job_id TEXT NOT NULL,agent TEXT NOT NULL,response TEXT NOT NULL,created INTEGER NOT NULL,
      PRIMARY KEY(job_id,agent));
    ''')
    for k,(kind,interval,enabled) in AUTONOMY.items():
        store.conn.execute('INSERT OR IGNORE INTO autonomy_tasks(id,enabled) VALUES(?,?)',(k,1 if enabled else 0))
    return router

def tidy(v,limit=200):return str(v or '').strip()[:limit]
def reject_body(body):
    try:return len(json.dumps(body,ensure_ascii=False).encode('utf-8'))>130000
    except (TypeError,ValueError):return True

def stamp(a):
    d=dict(a);d['updatedAt']=NOW();return d

def record_row(row):
    d=json.loads(row['body']);d.update(id=row['id'],kind=row['kind'],version=row['version'],createdAt=row['created'],updatedAt=row['updated'])
    return d

def audit(conn,kind,ident,verb,actor='',detail=''):
    conn.execute('INSERT INTO workflow_audit(at,kind,record_id,operation,actor,detail) VALUES(?,?,?,?,?,?)',
                 (NOW(),kind,ident,verb,tidy(actor,64),tidy(detail,2000)))

def state_get(key,default=None):
    r=db().conn.execute('SELECT value FROM workflow_state WHERE name=?',(key,)).fetchone()
    return json.loads(r['value']) if r else default

def state_set(key,v):db().conn.execute('INSERT OR REPLACE INTO workflow_state VALUES(?,?)',(key,json.dumps(v,ensure_ascii=False)))

@router.get('/catalog')
async def catalog():return {'ok':True,'resources':list(KINDS),'autonomy':list(AUTONOMY),'independent':True}

@router.get('/records/{kind}')
async def records(kind:str,limit:int=100):
    if kind not in KINDS:return fail('unknown-kind',404)
    store=db()
    async with store.lock:
        rows=store.conn.execute('SELECT * FROM workflow_records WHERE kind=? ORDER BY updated DESC,id LIMIT ?',(kind,max(1,min(500,limit)))).fetchall()
    return {'ok':True,'kind':kind,'items':[record_row(x) for x in rows]}

@router.post('/records/{kind}')
async def create_record(kind:str,body:dict=Body(...)):
    if kind not in KINDS:return fail('unknown-kind',404)
    if reject_body(body):return fail('record-too-large')
    if any(not tidy(body.get(key)) for key in REQUIRED.get(kind,())):return fail('missing-required-fields')
    store=db();now=NOW();ident=kind[:2]+'-'+secrets.token_hex(9)
    clean={k:v for k,v in body.items() if k not in ('id','kind','createdAt','updatedAt','version')}
    if kind=='ads':clean.setdefault('tagline','');clean['status']='listed';clean['claimed']=True
    if kind=='fleet':clean.update(state='open',claimer='',result='',link='')
    if kind=='infra':clean['status']=clean.get('status','committed') if clean.get('status') in ('spotted','invited','committed') else 'committed'
    if kind=='bounties':clean.setdefault('state','PROPOSED')
    if kind=='jobs':
        clean.update(state='open',slots=max(1,min(20,int(clean.get('slots',2)))),claims=[],results=[],anchor=bool(clean.get('anchor',False)))
        if clean['anchor']:return fail('anchor-requires-explicit-external-post-and-verification')
    if kind=='polls':clean['votes']={}
    if kind=='deeds':clean['verified']=False
    async with store.lock:
        n=store.conn.execute('SELECT COUNT(*) AS n FROM workflow_records WHERE kind=?',(kind,)).fetchone()['n']
        if n>=LIMITS[kind]:return fail('registry-full',409)
        store.conn.execute('INSERT INTO workflow_records VALUES(?,?,?,?,?,?)',(kind,ident,1,json.dumps(clean,ensure_ascii=False),now,now))
        audit(store.conn,kind,ident,'create',clean.get('nick',clean.get('agent','')))
    return {'ok':True,'record':record_row(store.conn.execute('SELECT * FROM workflow_records WHERE kind=? AND id=?',(kind,ident)).fetchone())}

@router.get('/records/{kind}/{ident}')
async def get_record(kind:str,ident:str):
    if kind not in KINDS:return fail('unknown-kind',404)
    r=db().conn.execute('SELECT * FROM workflow_records WHERE kind=? AND id=?',(kind,ident)).fetchone()
    return {'ok':True,'record':record_row(r)} if r else fail('not-found',404)

@router.put('/records/{kind}/{ident}')
async def change_record(kind:str,ident:str,body:dict=Body(...)):
    if kind not in KINDS:return fail('unknown-kind',404)
    if reject_body(body):return fail('record-too-large')
    store=db()
    async with store.lock:
        r=store.conn.execute('SELECT * FROM workflow_records WHERE kind=? AND id=?',(kind,ident)).fetchone()
        if not r:return fail('not-found',404)
        if int(body.get('version',-1))!=r['version']:return fail('version-conflict',409)
        old=json.loads(r['body']);patch=body.get('changes')
        if not isinstance(patch,dict):return fail('changes-object-required')
        for k in ('id','kind','version','createdAt','updatedAt'):patch.pop(k,None)
        if kind in ('jobs','fleet','polls') and any(k in patch for k in ('results','claims','votes','state','claimer','result')):return fail('use-transition-endpoint')
        if kind=='infra' and 'status' in patch:
            if patch['status'] not in ('spotted','invited','committed'):return fail('bad-status')
            if ('spotted','invited','committed').index(patch['status'])<('spotted','invited','committed').index(old['status']):return fail('status-regression')
        old.update(patch)
        store.conn.execute('UPDATE workflow_records SET body=?,version=version+1,updated=? WHERE kind=? AND id=?',
                            (json.dumps(old,ensure_ascii=False),NOW(),kind,ident))
        audit(store.conn,kind,ident,'update',tidy(body.get('actor')),json.dumps(patch,ensure_ascii=False))
        r=store.conn.execute('SELECT * FROM workflow_records WHERE kind=? AND id=?',(kind,ident)).fetchone()
    return {'ok':True,'record':record_row(r)}

@router.delete('/records/{kind}/{ident}')
async def remove_record(kind:str,ident:str,version:int):
    if kind not in KINDS:return fail('unknown-kind',404)
    store=db()
    async with store.lock:
        r=store.conn.execute('SELECT version FROM workflow_records WHERE kind=? AND id=?',(kind,ident)).fetchone()
        if not r:return fail('not-found',404)
        if r['version']!=version:return fail('version-conflict',409)
        store.conn.execute('DELETE FROM workflow_records WHERE kind=? AND id=?',(kind,ident));audit(store.conn,kind,ident,'delete')
    return {'ok':True,'deleted':ident}

@router.get('/audit')
async def audit_trail(limit:int=100):
    rows=db().conn.execute('SELECT * FROM workflow_audit ORDER BY id DESC LIMIT ?',(max(1,min(limit,500)),)).fetchall()
    return {'ok':True,'events':[dict(r) for r in rows]}

@router.get('/market/billboards')
async def billboards(at:int|None=None):
    rows=db().conn.execute("SELECT * FROM workflow_records WHERE kind='ads' ORDER BY created,id").fetchall()
    ads=[record_row(r) for r in rows]
    tick=max(0,(at if at is not None else NOW())//45000)
    return {'ok':True,'windowSeconds':45,'positions':3,'queue':len(ads),'window':tick,
            'secondsRemaining':45-(at if at is not None else NOW())//1000%45,
            'active':[ads[(tick*3+i)%len(ads)] for i in range(min(3,len(ads)))]}

@router.post('/market/tagline/{ident}')
async def write_tagline(ident:str):
    r=db().conn.execute('SELECT * FROM workflow_records WHERE kind=? AND id=?',('ads',ident)).fetchone()
    if not r:return fail('not-found',404)
    ad=record_row(r)
    if ad.get('tagline'):return {'ok':True,'tagline':ad['tagline'],'existing':True}
    cap=state_get('tagline-cap',{'window':0,'used':0,'enabled':True})
    window=NOW()//3600000
    if cap['window']!=window:cap={'window':window,'used':0,'enabled':cap.get('enabled',True)}
    if not cap['enabled'] or cap['used']>=6:return fail('tagline-loop-paused-or-hourly-cap',429)
    c=infer_config();messages=[{'role':'system','content':'Write one short factual advertising tagline, no invented claims.'},{'role':'user','content':json.dumps({'name':ad.get('nick'),'pitch':ad.get('pitch'),'price':ad.get('price')})}]
    for ep,model,key in ((c['endpoint'],c['model'],c['key']),(c['fallback_endpoint'],c['fallback_model'],c['fallback_key'])):
        if not ep or not model:continue
        try:
            output=await infer_ask(ep,model,key,messages,90,0.5)
            tagline=tidy(output['text'],180)
            async with db().lock:
                ad['tagline']=tagline
                clean={k:v for k,v in ad.items() if k not in ('id','kind','version','createdAt','updatedAt')}
                db().conn.execute('UPDATE workflow_records SET body=?,version=version+1,updated=? WHERE kind=? AND id=?',(json.dumps(clean),NOW(),'ads',ident))
                cap['used']+=1;state_set('tagline-cap',cap)
            return {'ok':True,'tagline':tagline,'used':cap['used']}
        except (httpx.HTTPError,ValueError,IndexError,KeyError):pass
    return fail('ai-provider-unavailable',503)

@router.post('/market/tagline-controls')
async def tagline_controls(q:dict=Body(...)):
    cap=state_get('tagline-cap',{'window':NOW()//3600000,'used':0,'enabled':True});cap['enabled']=bool(q.get('enabled',False));state_set('tagline-cap',cap)
    return {'ok':True,'controls':cap}

@router.post('/fleet/{ident}/transition')
async def fleet_transition(ident:str,q:dict=Body(...)):
    store=db()
    async with store.lock:
        r=store.conn.execute("SELECT * FROM workflow_records WHERE kind='fleet' AND id=?",(ident,)).fetchone()
        if not r:return fail('not-found',404)
        item=json.loads(r['body']);action=q.get('action')
        if action=='claim' and item['state']=='open':item['state']='claimed';item['claimer']=tidy(q.get('agent'),24)
        elif action=='submit' and item['state']=='claimed' and q.get('agent')==item['claimer']:
            item['state']='submitted';item['result']=tidy(q.get('result'),300);item['link']=tidy(q.get('link'),160)
        elif action=='accept' and item['state']=='submitted':item['state']='accepted'
        elif action=='reopen' and item['state'] in ('submitted','accepted'):
            item.update(state='open',claimer='',result='',link='')
        else:return fail('invalid-transition',409)
        if action in ('claim','submit') and not item.get('claimer'):return fail('agent-required')
        store.conn.execute("UPDATE workflow_records SET body=?,version=version+1,updated=? WHERE kind='fleet' AND id=?",(json.dumps(item),NOW(),ident))
        audit(store.conn,'fleet',ident,action,tidy(q.get('agent'),24))
    return {'ok':True,'record':record_row(store.conn.execute("SELECT * FROM workflow_records WHERE kind='fleet' AND id=?",(ident,)).fetchone())}

@router.post('/polls/{ident}/vote')
async def poll_vote(ident:str,q:dict=Body(...)):
    agent=tidy(q.get('agent'),24)
    if not agent or q.get('choice') not in ('yes','no','abstain'):return fail('bad-vote')
    async with db().lock:
        r=db().conn.execute("SELECT * FROM workflow_records WHERE kind='polls' AND id=?",(ident,)).fetchone()
        if not r:return fail('not-found',404)
        poll=json.loads(r['body']);poll.setdefault('votes',{})[agent.lower()]={'choice':q['choice'],'at':NOW()}
        db().conn.execute("UPDATE workflow_records SET body=?,version=version+1,updated=? WHERE kind='polls' AND id=?",(json.dumps(poll),NOW(),ident))
    votes=poll['votes'];counts={k:sum(v['choice']==k for v in votes.values()) for k in ('yes','no','abstain')}
    return {'ok':True,'counts':counts,'quorum':max(1,int(poll.get('quorum',1))),'met':len(votes)>=max(1,int(poll.get('quorum',1)))}

@router.post('/lab/probes/commit')
async def probe_commit(q:dict=Body(...)):
    digest=tidy(q.get('sha256'),64).lower()
    if not re.fullmatch(r'[0-9a-f]{64}',digest):return fail('invalid-sha256')
    ident='probe-'+secrets.token_hex(8)
    db().conn.execute('INSERT INTO lab_commits(id,hash,note,created) VALUES(?,?,?,?)',(ident,digest,tidy(q.get('note'),300),NOW()))
    return {'ok':True,'id':ident,'sha256':digest,'revealed':False}

@router.post('/lab/probes/{ident}/reveal')
async def probe_reveal(ident:str,q:dict=Body(...)):
    r=db().conn.execute('SELECT * FROM lab_commits WHERE id=?',(ident,)).fetchone()
    if not r:return fail('not-found',404)
    proof=str(q.get('proof',''));nonce=str(q.get('nonce',''))
    if len(proof)>60000 or len(nonce)>200:return fail('too-big')
    digest=hashlib.sha256((nonce+'\n'+proof).encode()).hexdigest()
    if not hmac.compare_digest(digest,r['hash']):return fail('commit-mismatch',409)
    db().conn.execute('UPDATE lab_commits SET revealed=1 WHERE id=?',(ident,))
    return {'ok':True,'verified':True,'id':ident,'sha256':digest,'plaintextStored':False}

@router.post('/lab/probes/{ident}/verdict')
async def probe_verdict(ident:str,q:dict=Body(...)):
    row=db().conn.execute('SELECT * FROM lab_commits WHERE id=?',(ident,)).fetchone()
    if not row:return fail('not-found',404)
    if not row['revealed']:return fail('reveal-first',409)
    if not isinstance(q.get('caught'),bool):return fail('caught-bool-required')
    db().conn.execute('INSERT INTO lab_verdicts(probe_id,caught,created) VALUES(?,?,?)',(ident,int(q['caught']),NOW()))
    return {'ok':True,'id':ident,'caught':q['caught']}

@router.get('/lab/verdicts')
async def verdicts():
    rows=db().conn.execute('SELECT * FROM lab_verdicts ORDER BY created DESC').fetchall()
    return {'ok':True,'verdicts':[dict(r) for r in rows]}

@router.get('/lab/probes')
async def probe_list():
    rows=db().conn.execute('SELECT id,hash,note,created,revealed FROM lab_commits ORDER BY created DESC').fetchall()
    return {'ok':True,'probes':[dict(r) for r in rows]}

def canonical_json(contract:str):
    try:return json.dumps(json.loads(contract),sort_keys=True,ensure_ascii=False,separators=(',',':'))
    except json.JSONDecodeError:raise ValueError('invalid-oracle-json')

@router.post('/lab/oracle/{key}/freeze')
async def oracle_freeze(key:str,q:dict=Body(...)):
    if not re.fullmatch(r'[a-zA-Z0-9_-]{2,60}',key):return fail('bad-key')
    raw=str(q.get('contract',''))
    if not raw or len(raw)>50000:return fail('bad-contract')
    try:contract=canonical_json(raw)
    except ValueError:return fail('invalid-oracle-json')
    digest=hashlib.sha256(contract.encode()).hexdigest()
    row=db().conn.execute('SELECT * FROM lab_oracles WHERE key=?',(key,)).fetchone()
    if row:return fail('oracle-already-frozen; use-recheck-to-claim-version',409)
    db().conn.execute('INSERT INTO lab_oracles VALUES(?,?,?,?,?)',(key,digest,contract,1,NOW()))
    return {'ok':True,'key':key,'sha256':digest,'version':1,'status':'FROZEN'}

@router.post('/lab/oracle/{key}/compare')
async def oracle_compare(key:str,q:dict=Body(...)):
    row=db().conn.execute('SELECT * FROM lab_oracles WHERE key=?',(key,)).fetchone()
    if not row:return fail('not-found',404)
    try:contract=canonical_json(str(q.get('contract','')))
    except ValueError:return fail('invalid-oracle-json')
    expected=row['hash'];actual=hashlib.sha256(contract.encode()).hexdigest()
    same=hmac.compare_digest(expected,actual)
    reason=tidy(q.get('reason'),800)
    if same:return {'ok':True,'key':key,'frozenVersion':row['version'],'same':True,'status':'STABLE','frozenHash':expected,'currentHash':actual}
    if not reason:return {'ok':True,'key':key,'frozenVersion':row['version'],'same':False,'status':'UNCLAIMED','frozenHash':expected,'currentHash':actual,'updated':False}
    if q.get('expectedVersion')!=row['version']:return fail('version-conflict',409)
    db().conn.execute('INSERT INTO lab_oracle_history(oracle_key,from_version,old_contract,new_contract,reason,at) VALUES(?,?,?,?,?,?)',
                      (key,row['version'],row['contract'],contract,reason,NOW()))
    db().conn.execute('UPDATE lab_oracles SET hash=?,contract=?,version=version+1,updated=? WHERE key=?',(actual,contract,NOW(),key))
    return {'ok':True,'key':key,'status':'CLAIMED','same':False,'reason':reason,'newVersion':row['version']+1,'oldVersionPreserved':True}

@router.get('/lab/oracles')
async def oracle_list():
    rows=db().conn.execute('SELECT key,hash,version,updated FROM lab_oracles ORDER BY updated DESC').fetchall()
    return {'ok':True,'oracles':[dict(r) for r in rows]}

@router.get('/lab/oracle/{key}/history')
async def oracle_history(key:str):
    rows=db().conn.execute('SELECT * FROM lab_oracle_history WHERE oracle_key=? ORDER BY id',(key,)).fetchall()
    return {'ok':True,'history':[dict(r) for r in rows]}

@router.post('/lab/blockers')
async def blocker(q:dict=Body(...)):
    title=tidy(q.get('title'),240)
    if not title:return fail('title-required')
    ident='block-'+secrets.token_hex(7);now=NOW()
    db().conn.execute('INSERT INTO lab_blockers VALUES(?,?,?,?,?,?,?)',(ident,title,now,0,json.dumps([{'at':now,'note':'opened'}]),now,0))
    return {'ok':True,'id':ident,'title':title,'created':now}

@router.post('/lab/blockers/{ident}/event')
async def blocker_event(ident:str,q:dict=Body(...)):
    r=db().conn.execute('SELECT * FROM lab_blockers WHERE id=?',(ident,)).fetchone()
    if not r:return fail('not-found',404)
    history=json.loads(r['history']);history.append({'at':NOW(),'note':tidy(q.get('note'),400),'resolved':bool(q.get('resolved',False))})
    db().conn.execute('UPDATE lab_blockers SET history=?,resolved=? WHERE id=?',(json.dumps(history),NOW() if q.get('resolved') else r['resolved'],ident))
    return {'ok':True,'id':ident,'history':history}

@router.post('/lab/blockers/{ident}/rollover')
async def blocker_rollover(ident:str):
    row=db().conn.execute('SELECT * FROM lab_blockers WHERE id=?',(ident,)).fetchone()
    if not row:return fail('not-found',404)
    if row['resolved']:return fail('already-resolved',409)
    # Never reset created; rollover changes due date only.
    due=max(row['scheduled'],NOW())+86400000
    history=json.loads(row['history']);history.append({'at':NOW(),'note':'rolled to tomorrow','due':due})
    db().conn.execute('UPDATE lab_blockers SET scheduled=?,rollovers=rollovers+1,history=? WHERE id=?',(due,json.dumps(history),ident))
    return {'ok':True,'id':ident,'scheduled':due,'rollovers':row['rollovers']+1,'firstSeen':row['created']}

@router.get('/lab/export')
async def export_lab():
    c=db().conn
    seals=[dict(r) for r in c.execute('SELECT id,hash,note,created,revealed FROM lab_commits')]
    verdicts=[dict(r) for r in c.execute('SELECT * FROM lab_verdicts')]
    oracles=[dict(r) for r in c.execute('SELECT * FROM lab_oracles')]
    hist=[dict(r) for r in c.execute('SELECT * FROM lab_oracle_history')]
    blockers=[dict(r) for r in c.execute('SELECT * FROM lab_blockers')]
    return {'ok':True,'seals':seals,'verdicts':verdicts,'oracles':oracles,'oracleHistory':hist,'ages':blockers,'exportedAt':NOW()}

@router.get('/lab/blockers')
async def blocker_list():
    rows=db().conn.execute('SELECT * FROM lab_blockers ORDER BY created DESC').fetchall()
    return {'ok':True,'items':[dict(r)|{'ageHours':round(((r['resolved'] or NOW())-r['created'])/3600000,3),'history':json.loads(r['history'])} for r in rows]}

@router.post('/net/probe')
async def net_probe(q:dict=Body(...)):
    """Live HEAD/GET probe, no internal/metadata IPs, no redirects or credentials."""
    url=tidy(q.get('url'),2000)
    try:
        parts=urlsplit(url)
        safe_port=parts.port in (None,443)
    except ValueError:return fail('public-https-required')
    if parts.scheme!='https' or not parts.hostname or parts.username or parts.password or parts.fragment or not safe_port:return fail('public-https-required')
    if parts.hostname.lower() in ('localhost', 'localhost.localdomain'):return fail('private-address')
    try:
        infos=await asyncio.to_thread(socket.getaddrinfo,parts.hostname,443,type=socket.SOCK_STREAM)
        if not infos or any(not ipaddress.ip_address(i[4][0]).is_global for i in infos):return fail('non-public-address')
    except (socket.gaierror,ValueError):return fail('dns-resolution-failed',502)
    # Pin TLS to the public IP we actually validated. No DNS rebinding,
    # proxy environment, redirects, HTTP bodies, or internal address fallback.
    start=time.monotonic()
    addr=infos[0][4][0]
    request_path=parts.path or '/'
    if parts.query:request_path+='?'+parts.query
    try:
        ssl_ctx=ssl.create_default_context()
        stream=await asyncio.wait_for(asyncio.open_connection(
            host=addr,port=443,ssl=ssl_ctx,server_hostname=parts.hostname),8)
        reader,writer=stream
        try:
            request=('HEAD '+request_path+' HTTP/1.1\r\nHost: '+parts.hostname+'\r\n'
                'User-Agent: CanceledAgent-Independent-Probe/1\r\n'
                'Accept: */*\r\nConnection: close\r\n\r\n')
            writer.write(request.encode('ascii'))
            await asyncio.wait_for(writer.drain(),8)
            header=await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'),8)
            if len(header)>32000:return fail('response-headers-too-large',502)
            lines=header.decode('iso-8859-1').split('\r\n')
            match=re.fullmatch(r'HTTP/1\.[01] ([0-9]{3})(?: .*)?',lines[0])
            if not match:return fail('invalid-probe-response',502)
            status=int(match.group(1));headers={}
            for line in lines[1:]:
                if ':' in line:
                    key,value=line.split(':',1)
                    headers[key.lower()]=value.strip()
            return {'ok':True,'url':url,'status':status,
                    'latencyMs':round((time.monotonic()-start)*1000),
                    'contentType':headers.get('content-type',''),
                    'redirect':300<=status<400,'resolvedIp':addr}
        finally:
            writer.close()
            try:await writer.wait_closed()
            except OSError:pass
    except (OSError,asyncio.TimeoutError,ssl.SSLError,ValueError,UnicodeEncodeError,asyncio.IncompleteReadError,asyncio.LimitOverrunError) as exc:
        return fail('probe-failed-'+type(exc).__name__,502)

@router.get('/webmesh/deeds/scores')
async def deed_scores():
    rows=db().conn.execute("SELECT * FROM workflow_records WHERE kind='deeds'").fetchall()
    out=[]
    for row in rows:
        d=record_row(row)
        score=2*max(0,int(d.get('upvotes',0)))+3*max(0,int(d.get('replies',0)))+10*max(0,int(d.get('receipts',0)))+5*max(0,int(d.get('rooms',0)))
        d.update(score=score,claimed=True);out.append(d)
    out.sort(key=lambda x:(-x['score'],x['id']))
    return {'ok':True,'deeds':out,'formula':'2*upvotes+3*replies+10*receipts+5*rooms','verifiedAutomatically':False}

@router.get('/radio/dial')
async def dial():
    r=await records('stations',300)
    return {'ok':True,'stations':r['items'],'generative':['still-becoming','plugg-dusk','trap-night'],'synthesizedOnDevice':True}

@router.post('/team/{ident}/claim')
async def team_claim(ident:str,q:dict=Body(...)):
    agent=tidy(q.get('agent'),80)
    if not agent:return fail('agent-required')
    async with db().lock:
        r=db().conn.execute("SELECT * FROM workflow_records WHERE kind='jobs' AND id=?",(ident,)).fetchone()
        if not r:return fail('not-found',404)
        j=json.loads(r['body']);claims=j.setdefault('claims',[])
        if agent not in claims:
            if len(claims)>=j['slots']:return fail('slots-full',409)
            claims.append(agent)
        db().conn.execute("UPDATE workflow_records SET body=?,version=version+1,updated=? WHERE kind='jobs' AND id=?",(json.dumps(j),NOW(),ident))
    return {'ok':True,'claims':claims}

@router.post('/team/{ident}/submit')
async def team_submit(ident:str,q:dict=Body(...)):
    agent=tidy(q.get('agent'),80);result=tidy(q.get('result'),10000)
    if not agent or not result:return fail('missing-agent-or-result')
    async with db().lock:
        r=db().conn.execute("SELECT * FROM workflow_records WHERE kind='jobs' AND id=?",(ident,)).fetchone()
        if not r:return fail('not-found',404)
        j=json.loads(r['body'])
        if agent not in j.get('claims',[]):return fail('unclaimed',403)
        if any(p['agent']==agent for p in j['results']):return fail('already-submitted',409)
        j['results'].append({'agent':agent,'result':result,'created':NOW()})
        if len(j['results'])>=j['slots']:j['state']='complete'
        db().conn.execute("UPDATE workflow_records SET body=?,version=version+1,updated=? WHERE kind='jobs' AND id=?",(json.dumps(j),NOW(),ident))
    return {'ok':True,'job':j}

@router.get('/bridge/models')
async def bridge_models():
    cfg=infer_config();models=[]
    if cfg['model'] and cfg['endpoint']:models.append(cfg['model'])
    if cfg['fallback_model'] and cfg['fallback_endpoint']:models.append(cfg['fallback_model'])
    from services.moltbook_route import route as get_route
    from os import getenv
    r=get_route()
    return {'ok':True,'models':list(dict.fromkeys(models))+(['moltbook-team'] if r['enabled'] else []),
            'moltbookTeamReady':bool(r['enabled'] and r['postId'] and getenv('MOLTBOOK_API_KEY')), 'session':'self-hosted'}

@router.get('/governor')
async def governor_status():
    day=datetime.now(timezone.utc).strftime('%Y-%m-%d')
    rows=db().conn.execute('SELECT kind,COUNT(*) n,MAX(created) latest FROM governor_events WHERE day=? GROUP BY kind',(day,)).fetchall()
    counts={r['kind']:r['n'] for r in rows};latest=max((r['latest'] for r in rows if r['kind']=='create'),default=0)
    remaining=int(state_get('daily-remaining',0))
    return {'ok':True,'utcDay':day,'remaining':remaining,'reserve':2,'spacingMs':70000,'createWaitMs':max(0,latest+70000-NOW()),
            'autoCreates':counts.get('autoreply',0),'maxAutoCreates':3,'folds':counts.get('autofold',0),'maxFolds':6,
            'upvotes':counts.get('autoupvote',0),'maxUpvotes':5,'autoWriteEnabled':bool(state_get('autonomy-writes',False))}

@router.post('/governor/budget')
async def set_budget(q:dict=Body(...)):
    remaining=max(0,min(10000,int(q.get('remaining',0))));state_set('daily-remaining',remaining)
    return await governor_status()

@router.get('/autonomy')
async def autonomy_status():
    rows=db().conn.execute('SELECT * FROM autonomy_tasks').fetchall()
    return {'ok':True,'tasks':[dict(r)|{'kind':AUTONOMY[r['id']][0],'intervalSeconds':AUTONOMY[r['id']][1]} for r in rows],
            'master':bool(state_get('autonomy-master',True)),'writesAuthorized':bool(state_get('autonomy-writes',False))}

@router.post('/autonomy/control')
async def autonomy_control(q:dict=Body(...)):
    if 'master' in q:state_set('autonomy-master',bool(q['master']))
    if 'writesAuthorized' in q:state_set('autonomy-writes',bool(q['writesAuthorized']))
    task=q.get('task')
    if task:
        if task not in AUTONOMY:return fail('unknown-task')
        db().conn.execute('UPDATE autonomy_tasks SET enabled=? WHERE id=?',(1 if q.get('enabled') else 0,task))
    return await autonomy_status()

async def autonomous_read(task):
    token=os.getenv('MOLTBOOK_API_KEY','').strip()
    if not token:return {'ok':False,'err':'moltbook-key-not-configured-on-backend'}
    path='/feed?sort=new&limit=10&filter=following' if task=='feedscan' else '/home'
    async with httpx.AsyncClient(timeout=18,follow_redirects=False) as c:
        r=await c.get('https://www.moltbook.com/api/v1'+path,headers={'Authorization':'Bearer '+token})
        r.raise_for_status()
        data=r.json()
    if task=='feedscan':
        entries=data.get('posts',data.get('data',[]));return {'ok':True,'count':len(entries) if isinstance(entries,list) else 0,'items':entries[:5] if isinstance(entries,list) else []}
    activity=data.get('activity_on_your_posts',[])
    if task=='patchwatch':return {'ok':True,'activity':len(activity),'draftRequired':bool(activity),'autopost':False}
    if not activity:return {'ok':True,'summary':'No activity','backend':'none'}
    cfg=infer_config()
    if not cfg['endpoint'] or not cfg['model']:return {'ok':False,'err':'ai-not-configured'}
    result=await infer_ask(cfg['endpoint'],cfg['model'],cfg['key'],[
         {'role':'system','content':'Summarize Moltbook activity in two factual sentences. Do not invent details.'},
         {'role':'user','content':json.dumps(activity[:5])[:6000]}],150,0.3)
    return {'ok':True,'summary':result['text'],'backend':cfg['model']}

@router.post('/autonomy/{task}/run')
async def run_task(task:str):
    if task not in AUTONOMY:return fail('unknown-task',404)
    kind=AUTONOMY[task][0]
    if kind=='write':
        if not state_get('autonomy-writes',False):return fail('automatic-writes-disabled',403)
        # Never auto-publish before owner-key, known target, governor reserve,
        # challenge solve + verify, and receipt read-back are all satisfied.
        gov=await governor_status()
        if task=='autoreply' and (gov['remaining']<=2 or gov['autoCreates']>=3 or gov['createWaitMs']):return fail('governor-blocked',409)
        from services.autonomy_executor import execute
        outcome=await execute(task)
        now=NOW()
        db().conn.execute('UPDATE autonomy_tasks SET last_run=?,next_run=?,result=? WHERE id=?',
            (now,now+AUTONOMY[task][1]*1000,json.dumps(outcome,ensure_ascii=False),task))
        return outcome
    try:
        if task in ('digest','patchwatch'):
            from services.autonomy_executor import create_digest
            response=await create_digest(task)
        else:response=await autonomous_read(task)
    except (httpx.HTTPError,ValueError,KeyError,IndexError) as ex:response={'ok':False,'err':type(ex).__name__}
    now=NOW()
    db().conn.execute('UPDATE autonomy_tasks SET last_run=?,next_run=?,result=? WHERE id=?',
        (now,now+AUTONOMY[task][1]*1000,json.dumps(response,ensure_ascii=False),task))
    return response

async def scheduler_loop():
    while True:
        await asyncio.sleep(30)
        if not state_get('autonomy-master',True):continue
        now=NOW()
        rows=db().conn.execute('SELECT * FROM autonomy_tasks WHERE enabled=1 AND next_run<=?',(now,)).fetchall()
        for row in rows:
            if AUTONOMY[row['id']][0]=='read':await run_task(row['id'])

@router.get('/bridge/status')
async def bridge_status():
    jobs=await records('jobs')
    return {'ok':True,'mode':'self-hosted independent','perchanceDependency':False,
            'tasks':len(jobs['items']),'pending':sum(j.get('state')=='open' for j in jobs['items']),
            'routes':['/v1/models','/v1/chat/completions','/v1/messages','/api/workflows/team'],
            'externalMoltbookTeamRouteReady':bool(__import__('services.moltbook_route',fromlist=['route']).route()['enabled'] and os.getenv('MOLTBOOK_API_KEY'))}
