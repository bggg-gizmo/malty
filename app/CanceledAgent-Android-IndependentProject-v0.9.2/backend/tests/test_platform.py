import pytest
from httpx import ASGITransport, AsyncClient
from services.rooms_votes.app import create_app

@pytest.mark.asyncio
async def test_project_edit_revision_conflict_and_revert(tmp_path):
    app=create_app(str(tmp_path/'platform.db'))
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://test') as c:
        created=(await c.post('/api/platform/projects',json={'name':'Demo','source':'<h1>A</h1>'})).json()['project']
        pid=created['id']
        assert created['revision']==1
        changed=await c.put(f'/api/platform/projects/{pid}',json={'source':'<h1>B</h1>','expectedRevision':1,'label':'B'})
        assert changed.json()['project']['revision']==2
        conflict=await c.put(f'/api/platform/projects/{pid}',json={'source':'stale','expectedRevision':1})
        assert conflict.status_code==409
        reverted=await c.post(f'/api/platform/projects/{pid}/revert/1',json={'expectedRevision':2})
        assert reverted.json()['project']['source']=='<h1>A</h1>'
        assert reverted.json()['project']['revision']==3
        history=(await c.get(f'/api/platform/projects/{pid}/revisions')).json()['revisions']
        assert [x['revision'] for x in history]==[3,2,1]

@pytest.mark.asyncio
async def test_backstep_links_and_feedback(tmp_path):
    app=create_app(str(tmp_path/'platform.db'))
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://test') as c:
        thought=(await c.post('/api/platform/thinking/build',json={'text':'Old design','kind':'think'})).json()['entry']
        step=(await c.post('/api/platform/thinking/build',json={'text':'Do not use old design','kind':'backstep','supersedes':thought['id']})).json()['entry']
        entries=(await c.get('/api/platform/thinking/build')).json()['entries']
        assert entries[0]['supersededBy']==step['id']
        assert step['parentId']==thought['id']
        feedback=(await c.get('/api/platform/thinking/build/feedback')).json()
        assert feedback['backsteps']==['Do not use old design']
        invalid=await c.post('/api/platform/thinking/build',json={'text':'x','kind':'backstep','supersedes':'missing'})
        assert invalid.status_code==404

@pytest.mark.asyncio
async def test_source_units_and_no_delete_on_revert(tmp_path):
    app=create_app(str(tmp_path/'platform.db'))
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://test') as c:
        p=(await c.post('/api/platform/projects',json={'name':'Emoji','source':'🦞'})).json()['project']
        q=await c.put(f'/api/platform/projects/{p["id"]}',json={'source':'🦞🦞','expectedRevision':1})
        assert q.status_code==200
        ids=(await c.get('/api/platform/projects')).json()['projects']
        assert len(ids)==1

@pytest.mark.asyncio
async def test_inference_configuration_does_not_expose_keys(tmp_path,monkeypatch):
    from services.inference import validate_url
    monkeypatch.setenv('AGENT_AI_KEY','top-secret')
    monkeypatch.setenv('AGENT_AI_ENDPOINT','https://model.example/v1/chat/completions')
    monkeypatch.setenv('AGENT_AI_MODEL','model')
    assert validate_url('https://models.example/v1/chat/completions')
    assert validate_url('http://127.0.0.1:8080/v1/chat/completions')
    assert not validate_url('http://models.example/v1/chat/completions')
    assert not validate_url('file:///etc/passwd')
    app=create_app(str(tmp_path/'platform.db'))
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://test') as c:
        status=(await c.get('/api/ai/status')).json()
        assert status['configured']
        assert 'top-secret' not in str(status)

@pytest.mark.asyncio
async def test_inference_provider_mock(tmp_path,monkeypatch):
    from services import inference
    async def fake_ask(endpoint,model,key,messages,max_tokens,temperature):
        assert key=='test-secret'
        assert messages[-1]['content']=='hello'
        return {'text':'world','raw':{}}
    monkeypatch.setattr(inference,'ask',fake_ask)
    monkeypatch.setenv('AGENT_AI_ENDPOINT','https://models.example/v1/chat/completions')
    monkeypatch.setenv('AGENT_AI_MODEL','test-model')
    monkeypatch.setenv('AGENT_AI_KEY','test-secret')
    app=create_app(str(tmp_path/'platform.db'))
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://test') as c:
        r=await c.post('/api/ai/generate',json={'prompt':'hello'})
        assert r.json()['text']=='world'
        assert r.json()['backend']=='primary'

@pytest.mark.asyncio
async def test_backend_token_protects_api(tmp_path,monkeypatch):
    monkeypatch.setenv('AGENT_BACKEND_TOKEN','unique-test-token')
    app=create_app(str(tmp_path/'platform.db'))
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://test') as c:
        assert (await c.get('/api/platform/projects')).status_code==401
        assert (await c.get('/api/rooms')).status_code==401
        assert (await c.get('/api/platform/projects',headers={'Authorization':'Bearer unique-test-token'})).status_code==200
        assert (await c.get('/health')).status_code==200
