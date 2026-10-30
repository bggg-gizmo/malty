"""Regression tests for actual routed Moltbook delegation and write safety."""
import hashlib
import pytest
from fastapi.testclient import TestClient
from services.rooms_votes.app import create_app
from services.workflows import state_set, NOW
import services.moltbook_route as route
import services.autonomy_executor as autonomy

@pytest.fixture
def c(tmp_path):
    with TestClient(create_app(str(tmp_path/'delegation.db'))) as client:
        yield client

def test_route_off_by_default_and_requires_explicit_optin(c):
    assert not c.get('/api/workflows/moltbook/route').json()['route']['enabled']
    assert c.post('/api/workflows/moltbook/ask',json={'prompt':'hi','optIn':False}).status_code==403
    assert c.post('/api/workflows/moltbook/ask',json={'prompt':'hi','optIn':True}).status_code==409
    assert c.post('/v1/chat/completions',json={'model':'moltbook-team','messages':[{'role':'user','content':'hi'}]}).status_code==400

def test_cache_does_not_spend_even_when_daily_cap_exhausted(c):
    cfg={'enabled':True,'postId':'post-a','roster':[],'quorum':1,'dailyCap':1,'cacheTtlMin':60}
    assert c.post('/api/workflows/moltbook/route',json=cfg).json()['route']['enabled']
    assert c.post('/api/workflows/governor/budget',json={'remaining':0}).json()['remaining']==0
    prompt='first question';agent=''
    key=hashlib.sha256(__import__('json').dumps([prompt,agent,'post-a',1]).encode()).hexdigest()
    replies=[{'agent':'test','commentId':'c-reply','text':'verified text'}]
    state_set('route-cache',{key:{'jobId':'historic','replies':replies,'expiresAt':NOW()+200000}})
    state_set('route-budget',{'day':__import__('time').strftime('%Y-%m-%d',__import__('time').gmtime()),'count':1,'lastAt':NOW()})
    r=c.post('/api/workflows/moltbook/ask',json={'prompt':prompt,'optIn':True})
    assert r.json()['cached'] and r.json()['spend']==0
    completion=c.post('/v1/chat/completions',json={'model':'moltbook-team','messages':[{'role':'user','content':prompt}]})
    assert completion.status_code==200,completion.text
    assert completion.json()['moltbook']['cached']
    assert completion.json()['choices'][0]['message']['content']=='verified text'

def test_no_cache_when_route_disabled(c):
    assert c.post('/api/workflows/moltbook/ask',json={'optIn':True,'prompt':'something'}).status_code==409

def test_unrelated_comments_and_own_agent_never_count(monkeypatch,c):
    state_set('route-job-mb-sample',{
        'id':'mb-sample','status':'pending','postId':'post-a','commentId':'c-parent','responses':[],
        'agent':'','roster':[],'quorum':2,'createdAt':NOW()-5000,'expiresAt':NOW()+60000,
        'promptHash':'hash-a','done':False,'receipt':{'ok':True},'deliveryVisible':None,
    })
    monkeypatch.setenv('MOLTBOOK_AGENT_NAME','owner')
    async def mock_get(method,path,body=None,params=None):
        return {'ok':True,'json':{'comments':[
            {'id':'c-other','author':{'name':'random'},'content':'wrong thread'},
            {'id':'c-parent','author':{'name':'owner'},'content':'question',
             'replies':[{'id':'c-own','author':{'name':'owner'},'content':'owner cannot answer'},
                        {'id':'c-agent','author':{'name':'bob'},'content':'real response'}]},
        ]}}
    monkeypatch.setattr(route,'molt_request',mock_get)
    r=c.post('/api/workflows/moltbook/poll/mb-sample').json()
    assert r['ok'] and r['deliveryVisible']
    assert len(r['replies'])==1 and r['replies'][0]['agent']=='bob'
    assert r['replies'][0]['envelope']['author']=='bob'
    assert r['replies'][0]['envelope']['commitment']=='partial'

def test_patchwatch_gates_and_wire_version_increment(c):
    assert not autonomy.gate_reply('click here for guaranteed profit',[])['ok']
    assert not autonomy.gate_reply('https://example.org',[])['ok']
    assert not autonomy.gate_reply('hello',['hello'])['ok']
    assert autonomy.gate_reply('One verifiable fact.',[])['ok']
    data=autonomy.repair_fold('{"doc":"builds","lines":["Test passed"]}')
    assert data=={'doc':'builds','lines':['Test passed']}
    wire=autonomy.wire_next('WIRE v6 · builds=2',[])
    assert 'WIRE v7' in wire and 'builds=3' in wire
    with pytest.raises(ValueError):autonomy.repair_fold('{"doc":"builds","lines":["https://scam.example"]}')

def test_bridge_and_authentication(c,monkeypatch):
    monkeypatch.setenv('AGENT_BACKEND_TOKEN','a-long-secret')
    assert c.get('/api/workflows/catalog').status_code==401
    assert c.get('/api/workflows/catalog',headers={'Authorization':'Bearer a-long-secret'}).status_code==200
    assert c.get('/health').status_code==200
    assert c.post('/api/bridge/relay',json={'payload':{'op':'ping'}}).status_code==401
