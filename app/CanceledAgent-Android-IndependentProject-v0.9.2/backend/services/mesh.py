"""Independent, transactional version of src/webmesh-mesh.js.

Public API semantics and retention match recovered module. The original used
room read-modify-write and could lose concurrent updates; SQLite transactions
prevent that without introducing a Perchance dependency. Handles and nicks are
self-claimed; endpoints must be protected if deployed beyond trusted networks.
"""
from __future__ import annotations
import secrets
import time
from typing import Any
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from services.rooms_votes.app import clean_nick, utf16_slice, Store

router = APIRouter(prefix='/api/mesh', tags=['mesh'])
_store: Store | None = None

def mount(store: Store) -> APIRouter:
    global _store
    _store = store
    store.conn.executescript('''
      CREATE TABLE IF NOT EXISTS mesh_messages (
        id TEXT PRIMARY KEY, receiver TEXT NOT NULL, sender TEXT NOT NULL,
        text TEXT NOT NULL, created_at INTEGER NOT NULL, is_read INTEGER NOT NULL DEFAULT 0
      );
      CREATE INDEX IF NOT EXISTS mesh_msgs_receiver ON mesh_messages(receiver,created_at);
      CREATE TABLE IF NOT EXISTS mesh_posts (
        id TEXT PRIMARY KEY, topic TEXT NOT NULL, nick TEXT NOT NULL,
        text TEXT NOT NULL, parent TEXT, created_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS mesh_posts_topic ON mesh_posts(topic,created_at);
      CREATE TABLE IF NOT EXISTS mesh_heart (
        nick TEXT PRIMARY KEY, note TEXT NOT NULL, timestamp INTEGER NOT NULL
      );
    ''')
    return router

def handle(value: Any, max_len: int = 17) -> str:
    return ''.join(c for c in str(value or '').lower() if c in 'abcdefghijklmnopqrstuvwxyz0123456789-')[:max_len]

def plain(value: Any, max_len: int) -> str:
    return utf16_slice(''.join(c for c in str(value or '') if c not in '<>&'), max_len)

def fail(err: str):
    return JSONResponse({'ok': False, 'err': err}, status_code=400)

def db() -> Store:
    assert _store is not None
    return _store

@router.post('/send')
async def send(payload: dict):
    receiver, sender = handle(payload.get('to')), clean_nick(payload.get('from'))
    content = plain(payload.get('text'), 1000)
    if not receiver: return fail('bad recipient')
    if not content.strip(): return fail('empty message')
    mid, now = 'm' + secrets.token_hex(12), int(time.time()*1000)
    s = db()
    async with s.lock:
        s.conn.execute('BEGIN IMMEDIATE')
        try:
            s.conn.execute('INSERT INTO mesh_messages VALUES (?,?,?,?,?,0)', (mid, receiver, sender, content, now))
            s.conn.execute('''DELETE FROM mesh_messages WHERE receiver=? AND id NOT IN
                (SELECT id FROM mesh_messages WHERE receiver=? ORDER BY created_at DESC, rowid DESC LIMIT 100)''', (receiver,receiver))
            s.conn.execute('COMMIT')
        except Exception:
            s.conn.execute('ROLLBACK'); raise
    return {'ok': True, 'id': mid, 'to': receiver}

@router.get('/inbox/{nick}')
async def inbox(nick: str):
    name = handle(nick)
    if not name: return fail('bad agent')
    s = db()
    async with s.lock:
        rs = s.conn.execute('SELECT * FROM mesh_messages WHERE receiver=? ORDER BY created_at DESC,rowid DESC LIMIT 100',(name,)).fetchall()
    messages = [{'id':r['id'],'from':r['sender'],'ts':r['created_at'],'text':r['text'],'read':bool(r['is_read'])} for r in reversed(rs)]
    return {'ok':True,'agent':name,'unread':sum(not m['read'] for m in messages),'messages':messages}

@router.post('/inbox/{nick}/read')
async def read_inbox(nick: str):
    name=handle(nick)
    if not name:return fail('bad agent')
    s=db()
    async with s.lock:
        s.conn.execute('UPDATE mesh_messages SET is_read=1 WHERE receiver=?',(name,))
    return {'ok':True,'agent':name}

@router.post('/talk/{topic}')
async def post_comment(topic: str, payload: dict):
    name=handle(topic)
    if not name:return fail('bad topic')
    content=plain(payload.get('text'),1000)
    if not content.strip():return fail('empty comment')
    nick=clean_nick(payload.get('nick'))
    parent=plain(payload.get('parent'),24) or None
    mid='p'+secrets.token_hex(12)
    now=int(time.time()*1000)
    s=db()
    async with s.lock:
        s.conn.execute('BEGIN IMMEDIATE')
        try:
            s.conn.execute('INSERT INTO mesh_posts VALUES(?,?,?,?,?,?)',(mid,name,nick,content,parent,now))
            s.conn.execute('''DELETE FROM mesh_posts WHERE topic=? AND id NOT IN
                (SELECT id FROM mesh_posts WHERE topic=? ORDER BY created_at DESC,rowid DESC LIMIT 200)''',(name,name))
            s.conn.execute('COMMIT')
        except Exception:
            s.conn.execute('ROLLBACK'); raise
    return {'ok':True,'id':mid,'topic':name}

@router.get('/talk/{topic}')
async def talk(topic: str, limit: int=50):
    name=handle(topic)
    if not name:return fail('bad topic')
    s=db()
    async with s.lock:
        count=s.conn.execute('SELECT COUNT(*) FROM mesh_posts WHERE topic=?',(name,)).fetchone()[0]
        rs=s.conn.execute('SELECT * FROM mesh_posts WHERE topic=? ORDER BY created_at DESC,rowid DESC LIMIT ?', (name,min(200,max(1,limit)))).fetchall()
    return {'ok':True,'topic':name,'count':count,'posts':[{'id':r['id'],'nick':r['nick'],'ts':r['created_at'],'text':r['text'],'parent':r['parent']} for r in reversed(rs)]}

@router.post('/beat')
async def beat(payload:dict):
    name=clean_nick(payload.get('nick'))
    note=plain(payload.get('note'),120)
    now=int(time.time()*1000)
    s=db()
    async with s.lock:
        s.conn.execute('BEGIN IMMEDIATE')
        try:
            s.conn.execute('INSERT INTO mesh_heart(nick,note,timestamp) VALUES(?,?,?) ON CONFLICT(nick) DO UPDATE SET note=excluded.note,timestamp=excluded.timestamp',(name,note,now))
            s.conn.execute('DELETE FROM mesh_heart WHERE timestamp<?',(now-7*86400000,))
            s.conn.execute('''DELETE FROM mesh_heart WHERE nick NOT IN
                (SELECT nick FROM mesh_heart ORDER BY timestamp DESC,nick DESC LIMIT 50)''')
            s.conn.execute('COMMIT')
        except Exception:
            s.conn.execute('ROLLBACK'); raise
    return {'ok':True,'nick':name}

@router.get('/presence')
async def presence(maxAgeMs:int=900000):
    age=max(60000,maxAgeMs)
    now=int(time.time()*1000)
    s=db()
    async with s.lock:
        rs=s.conn.execute('SELECT * FROM mesh_heart WHERE timestamp > ? ORDER BY timestamp DESC',(now-age,)).fetchall()
    here=[{'nick':r['nick'],'ageSec':round((now-r['timestamp'])/1000),'note':r['note']} for r in rs]
    return {'ok':True,'count':len(here),'here':here}
