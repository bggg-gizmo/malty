"""Perchance-free project editing, persisted revision history, and thinking/backsteps.

Project source is stored verbatim; source revisions are transactional and immutable.
Model/API secrets are never accepted by these routes.  This service exposes only
user-authored local project documents, not a remote-code execution primitive.
"""
from __future__ import annotations

import json
import secrets
import time
from typing import Any
from fastapi import APIRouter, Body
from fastapi.responses import JSONResponse
from services.rooms_votes.app import Store, utf16_length

router = APIRouter(prefix='/api/platform', tags=['independent-platform'])
_store: Store | None = None
MAX_PROJECT_UNITS = 2_000_000
MAX_TRACE_UNITS = 20_000

def mount(store: Store) -> APIRouter:
    global _store
    _store = store
    store.conn.executescript('''
      CREATE TABLE IF NOT EXISTS projects (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, source TEXT NOT NULL,
        revision INTEGER NOT NULL, updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS project_revisions (
        project_id TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL,
        label TEXT NOT NULL, created_at INTEGER NOT NULL,
        PRIMARY KEY(project_id, revision)
      );
      CREATE TABLE IF NOT EXISTS trace_entries (
        id TEXT PRIMARY KEY, trace_key TEXT NOT NULL, seq INTEGER NOT NULL,
        kind TEXT NOT NULL, content TEXT NOT NULL, parent_id TEXT,
        superseded_by TEXT, change_label TEXT NOT NULL, snapshot TEXT NOT NULL,
        source TEXT NOT NULL, actor TEXT NOT NULL, created_at INTEGER NOT NULL,
        UNIQUE(trace_key, seq)
      );
      CREATE INDEX IF NOT EXISTS idx_trace_entries ON trace_entries(trace_key,seq);
    ''')
    return router

def db() -> Store:
    assert _store is not None
    return _store

def err(message: str, status: int = 400):
    return JSONResponse({'ok':False,'err':message}, status_code=status)

def ms() -> int:
    return int(time.time() * 1000)

def project_row(row) -> dict:
    return {'id':row['id'],'name':row['name'],'source':row['source'],'revision':row['revision'],'updatedAt':row['updated_at']}

@router.get('/projects')
async def list_projects():
    store = db()
    async with store.lock:
        rows=store.conn.execute('SELECT * FROM projects ORDER BY updated_at DESC,id').fetchall()
    return {'ok':True,'projects':[project_row(r) for r in rows]}

@router.post('/projects')
async def create_project(q: dict = Body(...)):
    name=str(q.get('name') or '').strip()
    source=str(q.get('source') or '')
    if not name or len(name)>120:return err('bad-name')
    if utf16_length(source)>MAX_PROJECT_UNITS:return err('too-big')
    pid='prj-'+secrets.token_hex(12)
    now=ms()
    store=db()
    async with store.lock:
        store.conn.execute('BEGIN IMMEDIATE')
        try:
            store.conn.execute('INSERT INTO projects VALUES(?,?,?,?,?)',(pid,name,source,1,now))
            store.conn.execute('INSERT INTO project_revisions VALUES(?,?,?,?,?)',(pid,1,source,'Initial revision',now))
            store.conn.execute('COMMIT')
        except Exception:
            store.conn.execute('ROLLBACK')
            raise
    return {'ok':True,'project':{'id':pid,'name':name,'source':source,'revision':1,'updatedAt':now}}

@router.get('/projects/{project_id}')
async def get_project(project_id: str):
    store=db()
    async with store.lock:
        row=store.conn.execute('SELECT * FROM projects WHERE id=?',(project_id,)).fetchone()
    return {'ok':True,'project':project_row(row)} if row else err('not-found',404)

@router.put('/projects/{project_id}')
async def update_project(project_id: str, q: dict = Body(...)):
    source=str(q.get('source') or '')
    if utf16_length(source)>MAX_PROJECT_UNITS:return err('too-big')
    name=q.get('name')
    label=str(q.get('label') or 'Edit')[:120]
    expected=q.get('expectedRevision')
    store=db()
    async with store.lock:
        store.conn.execute('BEGIN IMMEDIATE')
        try:
            row=store.conn.execute('SELECT * FROM projects WHERE id=?',(project_id,)).fetchone()
            if row is None:
                store.conn.execute('ROLLBACK');return err('not-found',404)
            if expected is not None and expected != row['revision']:
                store.conn.execute('ROLLBACK');return err('revision-conflict',409)
            name=str(name if name is not None else row['name']).strip()
            if not name or len(name)>120:
                store.conn.execute('ROLLBACK');return err('bad-name')
            rev=row['revision']+1
            now=ms()
            store.conn.execute('UPDATE projects SET source=?,name=?,revision=?,updated_at=? WHERE id=?',(source,name,rev,now,project_id))
            store.conn.execute('INSERT INTO project_revisions VALUES(?,?,?,?,?)',(project_id,rev,source,label,now))
            store.conn.execute('COMMIT')
        except Exception:
            store.conn.execute('ROLLBACK');raise
    return {'ok':True,'project':{'id':project_id,'name':name,'source':source,'revision':rev,'updatedAt':now}}

@router.get('/projects/{project_id}/revisions')
async def revisions(project_id: str):
    store=db()
    async with store.lock:
        rows=store.conn.execute('SELECT revision,label,created_at FROM project_revisions WHERE project_id=? ORDER BY revision DESC',(project_id,)).fetchall()
    return {'ok':True,'revisions':[{'revision':r['revision'],'label':r['label'],'createdAt':r['created_at']} for r in rows]}

@router.post('/projects/{project_id}/revert/{revision}')
async def revert_project(project_id: str, revision: int, q:dict=Body(default={})):
    store=db()
    async with store.lock:
        row=store.conn.execute('SELECT source FROM project_revisions WHERE project_id=? AND revision=?',(project_id,revision)).fetchone()
    if row is None:return err('revision-not-found',404)
    # A revert creates a NEW revision. Never overwrite or delete history.
    return await update_project(project_id, {'source':row['source'],'label':f'Revert to revision {revision}', 'expectedRevision':q.get('expectedRevision')})

@router.delete('/projects/{project_id}')
async def delete_project(project_id: str):
    store=db()
    async with store.lock:
        store.conn.execute('BEGIN IMMEDIATE')
        try:
            deleted=store.conn.execute('DELETE FROM projects WHERE id=?',(project_id,)).rowcount
            store.conn.execute('DELETE FROM project_revisions WHERE project_id=?',(project_id,))
            store.conn.execute('COMMIT')
        except Exception:
            store.conn.execute('ROLLBACK');raise
    return {'ok':True,'deleted':bool(deleted)}

def trace_row(row) -> dict:
    return {'id':row['id'],'key':row['trace_key'],'seq':row['seq'],'kind':row['kind'], 'text':row['content'],
            'parentId':row['parent_id'],'supersededBy':row['superseded_by'],
            'changeLabel':row['change_label'],'snapshot':row['snapshot'],'source':row['source'],
            'actor':row['actor'],'createdAt':row['created_at']}

@router.get('/thinking/{trace_key}')
async def get_trace(trace_key: str):
    store=db()
    async with store.lock:
        rows=store.conn.execute('SELECT * FROM trace_entries WHERE trace_key=? ORDER BY seq',(trace_key,)).fetchall()
    return {'ok':True,'key':trace_key,'entries':[trace_row(r) for r in rows]}

@router.post('/thinking/{trace_key}')
async def append_trace(trace_key: str, q:dict=Body(...)):
    if not trace_key or len(trace_key)>64:return err('bad-key')
    content=str(q.get('text') or '')
    if not content.strip() or utf16_length(content)>MAX_TRACE_UNITS:return err('bad-text')
    kind=str(q.get('kind') or 'think')
    if kind not in ('think','backstep','change','note','revert'):return err('bad-kind')
    target=q.get('supersedes') or q.get('parentId')
    entry_id='th-'+secrets.token_hex(12)
    store=db()
    async with store.lock:
        store.conn.execute('BEGIN IMMEDIATE')
        try:
            if target:
                tgt=store.conn.execute('SELECT id FROM trace_entries WHERE trace_key=? AND id=?',(trace_key,target)).fetchone()
                if tgt is None:
                    store.conn.execute('ROLLBACK');return err('parent-not-found',404)
            seq=store.conn.execute('SELECT COALESCE(MAX(seq),0)+1 FROM trace_entries WHERE trace_key=?',(trace_key,)).fetchone()[0]
            now=ms()
            args=(entry_id,trace_key,seq,kind,content,target,None,str(q.get('changeLabel') or '')[:120],
                  str(q.get('snapshot') or '')[:200000],str(q.get('source') or 'user')[:50],str(q.get('actor') or 'user')[:50],now)
            store.conn.execute('INSERT INTO trace_entries VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',args)
            if kind=='backstep' and target:
                store.conn.execute('UPDATE trace_entries SET superseded_by=? WHERE id=?',(entry_id,target))
            store.conn.execute('COMMIT')
        except Exception:
            store.conn.execute('ROLLBACK');raise
    return {'ok':True,'entry':{'id':entry_id,'key':trace_key,'seq':seq,'kind':kind,'text':content,'parentId':target,'supersededBy':None,'changeLabel':args[7],'snapshot':args[8],'source':args[9],'actor':args[10],'createdAt':now}}

@router.get('/thinking/{trace_key}/feedback')
async def trace_feedback(trace_key: str):
    r=await get_trace(trace_key)
    avoid=[e['text'] for e in r['entries'] if e['kind']=='backstep']
    return {'ok':True,'key':trace_key,'backsteps':avoid,'text':'BACKSTEPS — AVOID REPEATING:\n'+'\n'.join(avoid) if avoid else ''}
