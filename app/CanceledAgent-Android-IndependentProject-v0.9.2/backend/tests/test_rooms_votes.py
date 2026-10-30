import pytest
from fastapi.testclient import TestClient
from services.rooms_votes.app import create_app, clean_room, clean_nick, js_fnv, utf16_length


@pytest.fixture
def api(tmp_path):
    app = create_app(str(tmp_path / "rooms.sqlite"))
    with TestClient(app) as client:
        yield client


def test_health_is_independent(api):
    r = api.get('/health').json()
    assert r == {'ok': True, 'service': 'rooms-votes', 'perchanceDependency': False}


def test_room_normalization_matches_supplied_plugin():
    assert clean_room("__exchange") == "exchange"
    assert clean_room("Room: A_B") == "roomab"
    assert clean_room("x") == ""
    assert clean_room("A" * 31) == "a" * 24
    assert clean_nick('<nick&>') == 'nick'


def test_room_load_and_save(api):
    assert api.get('/api/rooms/lobby').json()['html'] == ''
    assert api.put('/api/rooms/lobby', json={'html': '<p>hi</p>'}).json()['ok']
    assert api.get('/api/rooms/lobby').json()['html'] == '<p>hi</p>'
    assert api.get('/api/rooms').json()['rooms'][0]['size'] == 9


def test_unicode_limit_counts_utf16_units(api):
    assert utf16_length('😀') == 2
    assert api.put('/api/rooms/lobby', json={'html': '😀' * 20_000}).json()['ok']
    bad = api.put('/api/rooms/lobby', json={'html': '😀' * 20_001}).json()
    assert bad == {'ok': False, 'err': 'too-big', 'max': 40000}


def test_oldest_eviction_after_forty(api):
    for i in range(40):
        assert api.put('/api/rooms/room'+str(i), json={'html': str(i)}).json()['ok']
    result = api.put('/api/rooms/extra', json={'html': 'new'}).json()
    assert result['ok'] and result['evicted'] == 'room0'
    rooms = api.get('/api/rooms').json()['rooms']
    assert len(rooms) == 40
    assert rooms[0]['name'] == 'extra'


def test_feature_flags_default_enabled(api):
    f = api.get('/api/votes').json()['features']
    assert len(f) == 6
    assert all(x['enabled'] and x['yes'] == 0 and x['no'] == 0 for x in f)


def test_vote_replace_and_duplicate(api):
    b = {'feat':'editor','dir':'off','nick':'tester','donor':False,'handle':''}
    assert api.post('/api/votes', json=b).json()['no'] == 1
    assert api.post('/api/votes', json=b).json()['dup'] is True
    b['dir'] = 'on'
    assert api.post('/api/votes', json=b).json()['yes'] == 1
    f = api.get('/api/votes').json()['features']
    assert f[2] == {'key':'editor', 'yes':1, 'no':0, 'enabled':True}


def test_donor_self_claim_is_double_weight(api):
    b = {'feat':'playground','dir':'off','nick':'supporter','donor':True,'handle':'cash'}
    assert api.post('/api/votes', json=b).json()['weight'] == 2
    b['nick'] = 'unclear'; b['handle'] = 'x'
    assert api.post('/api/votes', json=b).json()['weight'] == 1
    votes = api.get('/api/votes').json()['features']
    assert next(f for f in votes if f['key'] == 'playground')['no'] == 3


def test_realtime_room_presence_edit_and_rpc(api):
    with api.websocket_connect('/ws') as a:
        a.send_json({'t':'join','room':'lobby','nick':'one'})
        assert a.receive_json()['t'] == 'doc'
        assert a.receive_json()['t'] == 'presence'
        a.send_json({'t':'rpc','requestId':'r1','method':'save','data':{'room':'lobby','html':'first'}})
        # A published 'saved' event can arrive before the RPC result.
        x, y = a.receive_json(), a.receive_json()
        assert {x['t'],y['t']} == {'saved','rpc_result'}
        r = x if x['t'] == 'rpc_result' else y
        assert r['result']['ok']
        a.send_json({'t':'rpc','requestId':'r2','method':'save','data':{'room':'lobby','html':'second'}})
        r = a.receive_json()
        assert r['t'] == 'rpc_result' and r['result']['err'] == 'slow-down'
        with api.websocket_connect('/ws') as b:
            b.send_json({'t':'join','room':'lobby','nick':'two'})
            r = b.receive_json()
            assert r['html'] == 'first' and len(r['who']) == 2
            assert b.receive_json()['t'] == 'presence'
            assert a.receive_json()['t'] == 'presence'
            a.send_json({'t':'edit','html':'unsaved','mid':'m1'})
            assert a.receive_json()['t'] == 'edit'
            assert b.receive_json()['html'] == 'unsaved'
            b.send_json({'t':'leave'})
            assert a.receive_json()['t'] == 'presence'


def test_vote_socket_cooldown(api):
    with api.websocket_connect('/ws') as ws:
        b = {'feat':'replies','dir':'off','nick':'alice'}
        ws.send_json({'t':'rpc','requestId':'1','method':'vote','data':b})
        assert ws.receive_json()['result']['ok']
        b['dir'] = 'on'
        ws.send_json({'t':'rpc','requestId':'2','method':'vote','data':b})
        assert ws.receive_json()['result']['err'] == 'slow-down'


def test_ballot_capacity_384(api):
    for i in range(384):
        v = api.post('/api/votes', json={'feat':'donations','nick':f'user-{i}','dir':'on'}).json()
        assert v['ok'] is True, (i, v)
    err = api.post('/api/votes', json={'feat':'donations','nick':'overflow-user','dir':'on'}).json()
    assert err == {'ok': False, 'err': 'ballot-full'}


def test_persistence_reopen(tmp_path):
    path = str(tmp_path / 'persist.sqlite')
    with TestClient(create_app(path)) as a:
        a.put('/api/rooms/lobby', json={'html':'persisted'})
        a.post('/api/votes', json={'feat':'thinking','nick':'user','dir':'off'})
    with TestClient(create_app(path)) as b:
        assert b.get('/api/rooms/lobby').json()['html'] == 'persisted'
        assert next(x for x in b.get('/api/votes').json()['features'] if x['key']=='thinking')['no'] == 1


def test_realtime_edit_throttle(api):
    with api.websocket_connect('/ws') as ws:
        ws.send_json({'t':'join','room':'lobby','nick':'listener'})
        assert ws.receive_json()['t'] == 'doc'
        assert ws.receive_json()['t'] == 'presence'
        ws.send_json({'t':'edit','html':'first','mid':'one'})
        assert ws.receive_json()['html'] == 'first'
        ws.send_json({'t':'edit','html':'second','mid':'two'})
        ws.send_json({'t':'rpc','requestId':'check','method':'load','data':{'room':'lobby'}})
        # Invalid (too fast) edit is not published; the RPC reply arrives.
        assert ws.receive_json()['t'] == 'rpc_result'
        assert api.get("/api/rooms/lobby").json()["html"] == ""


def test_leave_rejoin_resets_save_throttle(api):
    with api.websocket_connect('/ws') as ws:
        ws.send_json({'t':'join','room':'lobby','nick':'alice'})
        assert ws.receive_json()['t'] == 'doc'
        assert ws.receive_json()['t'] == 'presence'
        ws.send_json({'t':'rpc','requestId':'before','method':'save','data':{'room':'lobby','html':'one'}})
        assert {ws.receive_json()['t'],ws.receive_json()['t']} == {'saved', 'rpc_result'}
        ws.send_json({'t':'leave'})
        ws.send_json({'t':'join','room':'lobby','nick':'alice'})
        assert ws.receive_json()['t'] == 'doc'
        assert ws.receive_json()['t'] == 'presence'
        ws.send_json({'t':'rpc','requestId':'after','method':'save','data':{'room':'lobby','html':'two'}})
        assert {ws.receive_json()['t'],ws.receive_json()['t']} == {'saved', 'rpc_result'}
        assert api.get('/api/rooms/lobby').json()['html'] == 'two'
