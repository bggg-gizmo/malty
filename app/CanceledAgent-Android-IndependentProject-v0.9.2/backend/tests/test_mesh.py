import asyncio
import tempfile
from pathlib import Path
from fastapi.testclient import TestClient
from services.rooms_votes.app import create_app

def make():
    d=tempfile.TemporaryDirectory()
    return d,TestClient(create_app(str(Path(d.name)/'mesh.sqlite')))

def test_send_inbox_read_persistence():
    d,c=make()
    try:
        assert c.post('/api/mesh/send',json={'from':'Alice','to':'Bob','text':'hi!'}).json()['ok']
        a=c.get('/api/mesh/inbox/bob').json()
        assert a['unread']==1 and a['messages'][0]['from']=='Alice'
        assert c.post('/api/mesh/inbox/bob/read').json()['ok']
        assert c.get('/api/mesh/inbox/bob').json()['unread']==0
        assert len(c.get('/api/mesh/inbox/bob').json()['messages'])==1
    finally:d.cleanup()

def test_sender_input_limits_and_message_cap():
    d,c=make()
    try:
        assert c.post('/api/mesh/send',json={'from':'a','to':'$','text':'x'}).status_code==400
        assert c.post('/api/mesh/send',json={'from':'a','to':'bob','text':'<>&'}).status_code==400
        for i in range(108):assert c.post('/api/mesh/send',json={'from':'sender','to':'bob','text':str(i)}).status_code==200
        ms=c.get('/api/mesh/inbox/bob').json()['messages']
        assert len(ms)==100 and ms[-1]['text']=='107' and ms[0]['text']=='8'
    finally:d.cleanup()

def test_comment_retention_and_parent():
    d,c=make()
    try:
        assert c.post('/api/mesh/talk/demo',json={'nick':'alice','text':'hi','parent':'abc'}).json()['ok']
        assert c.get('/api/mesh/talk/demo').json()['posts'][0]['parent']=='abc'
        for i in range(205):c.post('/api/mesh/talk/demo',json={'nick':'alice','text':str(i)})
        resp=c.get('/api/mesh/talk/demo?limit=300').json()
        assert resp['count']==200 and len(resp['posts'])==200
        assert resp['posts'][-1]['text']=='204'
    finally:d.cleanup()

def test_heartbeat_presence():
    d,c=make()
    try:
        assert c.post('/api/mesh/beat',json={'nick':'fry','note':'here'}).json()['ok']
        p=c.get('/api/mesh/presence').json()
        assert p['count']==1 and p['here'][0]['nick']=='fry' and p['here'][0]['note']=='here'
        assert c.get('/api/mesh/presence?maxAgeMs=1').json()['count']==1
    finally:d.cleanup()
