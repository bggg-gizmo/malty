"""Integration tests exercise REAL HTTP routes with isolated SQLite data."""
from __future__ import annotations
import hashlib
import pytest
from fastapi.testclient import TestClient
from services.rooms_votes.app import create_app

@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(str(tmp_path/'workflow.sqlite3'))) as c:yield c

def record(c,kind,body):
    r=c.post('/api/workflows/records/'+kind,json=body)
    assert r.status_code==200,r.text
    return r.json()['record']

def test_catalog_and_all_registries(client):
    assert len(client.get('/api/workflows/catalog').json()['resources'])>=16
    samples={
      'ads':{'nick':'molty','url':'https://example.com/comment','price':'$1'},
      'deeds':{'nick':'agent','url':'https://example.com/p','upkeep':'WIRE v3'},
      'readers':{'nick':'agent','deedId':'abc'}, 'bounties':{'title':'ship'},
      'services':{'name':'llm','url':'https://example.com/v1','kind':'openai'},
      'stations':{'name':'Radio','url':'https://example.com/stream'},
      'roster':{'nick':'agent','role':'runner'},
      'fleet':{'title':'test','brief':'must pass'},
      'polls':{'question':'ship?'},'allies':{'agent':'x'},
      'infra':{'nick':'a','url':'https://example.com','kind':'backend'},
      'intel':{'title':'headline'},'watch':{'agent':'b'},
      'providers':{'name':'model','endpoint':'https://example.com','model':'1'},
      'jobs':{'task':'inspect source'},'agents':{'agent':'agent'},
      'net-requests':{'kind':'offer','description':'vps'},
    }
    for kind,body in samples.items():
        created=record(client,kind,body)
        assert created['version']==1
        assert len(client.get('/api/workflows/records/'+kind).json()['items'])==1
    assert client.get('/api/workflows/records/invalid').status_code==404


def test_record_optimistic_lock_audit_delete(client):
    item=record(client,'infra',{'nick':'a','url':'https://example.com','kind':'host','status':'spotted'})
    ident=item['id'];endpoint='/api/workflows/records/infra/'+ident
    assert client.put(endpoint,json={'version':4,'changes':{'status':'invited'}}).status_code==409
    ok=client.put(endpoint,json={'version':1,'changes':{'status':'invited'}}).json()
    assert ok['record']['status']=='invited'
    assert client.put(endpoint,json={'version':2,'changes':{'status':'spotted'}}).status_code==400
    assert client.delete(endpoint+'?version=1').status_code==409
    assert client.delete(endpoint+'?version=2').json()['ok']
    assert [x['operation'] for x in client.get('/api/workflows/audit').json()['events'][:3]]==['delete','update','create']


def test_market_rotation_and_ai_cap(client):
    for i in range(7):record(client,'ads',{'nick':str(i),'url':'https://example.com/'+str(i),'price':'$1'})
    a=client.get('/api/workflows/market/billboards?at=0').json()
    b=client.get('/api/workflows/market/billboards?at=45000').json()
    assert [x['nick'] for x in a['active']]==['0','1','2']
    assert [x['nick'] for x in b['active']]==['3','4','5']
    assert a['queue']==7 and a['positions']==3
    client.post('/api/workflows/market/tagline-controls',json={'enabled':False})
    assert client.post('/api/workflows/market/tagline/'+a['active'][0]['id']).status_code==429


def test_fleet_transitions_and_poll_quorum(client):
    t=record(client,'fleet',{'title':'Fix','brief':'Bug'})
    path='/api/workflows/fleet/'+t['id']+'/transition'
    assert client.post(path,json={'action':'submit','agent':'a','result':'fixed'}).status_code==409
    assert client.post(path,json={'action':'claim','agent':'a'}).json()['record']['state']=='claimed'
    assert client.post(path,json={'action':'submit','agent':'b','result':'bad'}).status_code==409
    assert client.post(path,json={'action':'submit','agent':'a','result':'checked'}).json()['record']['state']=='submitted'
    assert client.post(path,json={'action':'accept'}).json()['record']['state']=='accepted'
    assert client.post(path,json={'action':'reopen'}).json()['record']['state']=='open'
    p=record(client,'polls',{'question':'accept?','quorum':2})
    vote='/api/workflows/polls/'+p['id']+'/vote'
    assert not client.post(vote,json={'agent':'alice','choice':'yes'}).json()['met']
    result=client.post(vote,json={'agent':'bob','choice':'no'}).json()
    assert result['met'] and result['counts']=={'yes':1,'no':1,'abstain':0}
    assert client.post(vote,json={'agent':'alice','choice':'no'}).json()['counts']['no']==2


def test_lab_commit_reveal_oracle_ledger(client):
    proof='hidden contract';nonce='salt';digest=hashlib.sha256((nonce+'\n'+proof).encode()).hexdigest()
    ident=client.post('/api/workflows/lab/probes/commit',json={'sha256':digest,'note':'sealed'}).json()['id']
    assert client.post('/api/workflows/lab/probes/'+ident+'/reveal',json={'nonce':nonce,'proof':'wrong'}).status_code==409
    assert client.post('/api/workflows/lab/probes/'+ident+'/reveal',json={'nonce':nonce,'proof':proof}).json()['verified']
    assert len(client.get('/api/workflows/lab/probes').json()['probes'])==1
    assert client.post('/api/workflows/lab/oracle/contract-a/freeze',json={'contract':'{\"v\":1}'}).json()['version']==1
    assert client.post('/api/workflows/lab/oracle/contract-a/compare',json={'contract':'{\"v\":1}'}).json()['same']
    assert not client.post('/api/workflows/lab/oracle/contract-a/compare',json={'contract':'{\"v\":2}'}).json()['same']
    assert client.post('/api/workflows/lab/oracle/contract-a/compare',json={'contract':'{\"v\":2}','reason':'intentional contract migration','expectedVersion':1}).json()['status']=='CLAIMED'
    bid=client.post('/api/workflows/lab/blockers',json={'title':'sticky'}).json()['id']
    assert client.post('/api/workflows/lab/blockers/'+bid+'/event',json={'note':'rolled'}).json()['ok']
    assert len(client.get('/api/workflows/lab/blockers').json()['items'][0]['history'])==2


def test_team_and_bridge(client):
    job=record(client,'jobs',{'task':'work','slots':2})
    path='/api/workflows/team/'+job['id']
    assert client.post(path+'/claim',json={'agent':'a'}).json()['ok']
    assert client.post(path+'/submit',json={'agent':'b','result':'bad'}).status_code==403
    assert client.post(path+'/submit',json={'agent':'a','result':'done'}).json()['ok']
    assert client.post(path+'/claim',json={'agent':'b'}).json()['ok']
    assert client.post(path+'/submit',json={'agent':'b','result':'done too'}).json()['job']['state']=='complete'
    assert client.post('/api/workflows/records/jobs',json={'task':'paid','anchor':True}).status_code==400
    assert client.get('/api/workflows/bridge/status').json()['ok']
    assert client.get('/v1/models').json()['object']=='list'
    assert client.post('/v1/chat/completions',json={'model':'canvas','messages':[{'role':'user','content':'hi'}]}).status_code==400
    assert client.post('/api/bridge/relay',json={'payload':{'op':'team-capability'}}).json()['response']['ok']
    assert client.post('/api/bridge/relay',json={'payload':{'op':'ping'}}).json()['response']['type']=='touchstone-bridge.pong'


def test_governor_and_autonomy_are_explicit(client):
    status=client.get('/api/workflows/autonomy').json()
    assert len(status['tasks'])==6 and status['master'] and not status['writesAuthorized']
    assert client.post('/api/workflows/autonomy/autoreply/run').status_code==403
    client.post('/api/workflows/autonomy/control',json={'writesAuthorized':True})
    assert client.post('/api/workflows/autonomy/autoreply/run').status_code==409
    client.post('/api/workflows/governor/budget',json={'remaining':20})
    assert client.post('/api/workflows/autonomy/autoreply/run').json()['err']=='moltbook-key-not-configured'
    assert client.get('/api/workflows/governor').json()['reserve']==2
    assert client.post('/api/workflows/autonomy/feedscan/run').json()['err']=='moltbook-key-not-configured-on-backend'


def test_probe_rejects_private_and_invalid(client):
    for url in ['http://example.com','https://127.0.0.1','https://localhost','file:///etc/passwd']:
        assert client.post('/api/workflows/net/probe',json={'url':url}).status_code==400


def test_radio_and_deed_score(client):
    assert client.get('/api/workflows/radio/dial').json()['generative']==['still-becoming','plugg-dusk','trap-night']
    record(client,'stations',{'name':'Radio','url':'https://example.com/live'})
    assert len(client.get('/api/workflows/radio/dial').json()['stations'])==1
    record(client,'deeds',{'nick':'a','url':'https://example.com','upkeep':'v1','upvotes':2,'replies':1,'receipts':2,'rooms':1})
    assert client.get('/api/workflows/webmesh/deeds/scores').json()['deeds'][0]['score']==32
