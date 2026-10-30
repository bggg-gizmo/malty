"""Standalone persistence, presence, realtime room events and feature voting.

Implements the observable behaviors of the supplied Perchance server-plugin in
independent FastAPI + SQLite code. No Perchance runtime, account or API required.

Local start: uvicorn services.rooms_votes.app:app --host 127.0.0.1 --port 8765
Network binding: configure AGENT_BACKEND_TOKEN and serve behind authenticated HTTPS.
Config: AGENT_DB_PATH (default ./agent-rooms.db)

The HTTP endpoints are native-friendly. WebSocket /ws accepts the plugin's
join/edit/leave messages plus request/reply RPC envelopes. Shared records are
committed to SQLite. The in-process websocket membership map requires a single
server process; run one Uvicorn worker, or swap broadcaster for a distributed
pub/sub before running multiple replicas.
"""
from __future__ import annotations

import asyncio
import json
import os
import sqlite3
import time
from dataclasses import dataclass
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from pydantic import BaseModel

MAX_ROOMS = 40
MAX_NAME_UNITS = 24
MAX_DOC_UNITS = 40_000
MAX_BALLOTS = 384
SAVE_COOLDOWN_MS = 2_000
EDIT_COOLDOWN_MS = 300
VOTE_COOLDOWN_MS = 5_000
FEATURES = ("donations", "touchscreen", "editor", "thinking", "playground", "replies")


def utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


def utf16_slice(text: str, limit: int) -> str:
    b = text.encode("utf-16-le", "surrogatepass")[: limit * 2]
    return b.decode("utf-16-le", "surrogatepass")


def clean_room(room: Any) -> str:
    """Match source's lower-case, strip-invalid, slice-24 room normalization.

    An important source edge case: '__foo' becomes 'foo' because underscores
    are stripped. Do not silently change this in a parity implementation.
    """
    normalized = "".join(c for c in str(room or "").lower() if c in "abcdefghijklmnopqrstuvwxyz0123456789-")
    return utf16_slice(normalized, MAX_NAME_UNITS) if len(normalized) >= 2 else ""


def clean_nick(nick: Any) -> str:
    normalized = "".join(c for c in str(nick or "molty") if c not in '<>&"')
    return utf16_slice(normalized, MAX_NAME_UNITS) or "molty"


def js_fnv(text: str) -> int:
    """Preserve supplied JS's float multiplication + unsigned conversion.

    The JS implementation uses `(h * 16777619) >>> 0` rather than Math.imul;
    that is not mathematically identical to 32-bit modular FNV multiplication.
    """
    h = 2166136261
    encoded = str(text).encode("utf-16-le", "surrogatepass")
    for i in range(0, len(encoded), 2):
        unit = encoded[i] | (encoded[i+1] << 8)
        h ^= unit
        h = int(float(h * 16777619)) & 0xFFFFFFFF
    return h


def now_ms() -> int:
    return int(time.time() * 1000)


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path, isolation_level=None, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=30000")
        self.conn.executescript("""
            CREATE TABLE IF NOT EXISTS rooms (
                slot INTEGER PRIMARY KEY CHECK(slot >= 0 AND slot < 40),
                name TEXT NOT NULL UNIQUE,
                html TEXT NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS ballots (
                slot INTEGER PRIMARY KEY CHECK(slot >= 0 AND slot < 384),
                feat INTEGER NOT NULL CHECK(feat >= 0 AND feat < 6),
                direction INTEGER NOT NULL CHECK(direction IN (0,1)),
                weight INTEGER NOT NULL CHECK(weight IN (1,2)),
                donor INTEGER NOT NULL CHECK(donor IN (0,1)),
                nick_hash INTEGER NOT NULL,
                net3 INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS uniq_ballots ON ballots(feat,nick_hash);
            CREATE TABLE IF NOT EXISTS schema_state (k TEXT PRIMARY KEY, v TEXT NOT NULL);
        """)
        self.lock = asyncio.Lock()

    async def rooms(self) -> dict:
        async with self.lock:
            rows = self.conn.execute("SELECT name, html, updated_at FROM rooms ORDER BY slot").fetchall()
            return {"ok": True, "rooms": [{"name": r["name"], "size": utf16_length(r["html"]), "updatedAt": r["updated_at"]} for r in rows]}

    async def load(self, room: Any) -> dict:
        room = clean_room(room)
        if not room:
            return {"ok": False, "err": "bad-room"}
        async with self.lock:
            row = self.conn.execute("SELECT html, updated_at FROM rooms WHERE name = ?", (room,)).fetchone()
            return {"ok": True, "room": room, "html": row["html"] if row else "", "updatedAt": row["updated_at"] if row else 0}

    async def save(self, room: Any, html: Any, ts: int | None = None) -> dict:
        room = clean_room(room)
        html = str(html or "")
        if not room:
            return {"ok": False, "err": "bad-room"}
        if utf16_length(html) > MAX_DOC_UNITS:
            return {"ok": False, "err": "too-big", "max": MAX_DOC_UNITS}
        when = now_ms() if ts is None else ts
        async with self.lock:
            db = self.conn
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT slot FROM rooms WHERE name = ?", (room,)).fetchone()
                evicted = ""
                if row:
                    slot = int(row["slot"])
                else:
                    count = db.execute("SELECT COUNT(*) AS count FROM rooms").fetchone()["count"]
                    if count >= MAX_ROOMS:
                        old = db.execute("SELECT slot, name FROM rooms WHERE substr(name,1,2) != '__' ORDER BY updated_at ASC, slot ASC LIMIT 1").fetchone()
                        if old is None:
                            old = db.execute("SELECT slot, name FROM rooms ORDER BY slot LIMIT 1").fetchone()
                        slot = int(old["slot"])
                        evicted = old["name"]
                        db.execute("DELETE FROM rooms WHERE slot = ?", (slot,))
                    else:
                        present = {r["slot"] for r in db.execute("SELECT slot FROM rooms").fetchall()}
                        slot = next(i for i in range(MAX_ROOMS) if i not in present)
                db.execute("INSERT OR REPLACE INTO rooms(slot,name,html,updated_at) VALUES(?,?,?,?)", (slot, room, html, when))
                db.execute("COMMIT")
                return {"ok": True, "room": room, "evicted": evicted}
            except Exception:
                db.execute("ROLLBACK")
                raise

    async def features(self) -> dict:
        async with self.lock:
            rows = self.conn.execute("SELECT feat, direction, SUM(weight) AS total FROM ballots GROUP BY feat,direction").fetchall()
            count = {(int(r["feat"]), int(r["direction"])): int(r["total"]) for r in rows}
            return {"ok": True, "features": [
                {"key": feature, "yes": count.get((idx, 1), 0), "no": count.get((idx, 0), 0),
                 "enabled": count.get((idx, 1), 0) >= count.get((idx, 0), 0)}
                for idx, feature in enumerate(FEATURES)
            ]}

    async def vote(self, feat: Any, direction: Any, nick: Any, donor: Any, handle: Any, net3: int = 0, ts: int | None = None) -> dict:
        if feat not in FEATURES:
            return {"ok": False, "err": "bad-feature"}
        f = FEATURES.index(feat)
        dir_v = 0 if direction == "off" else 1
        claimed = donor is True and utf16_length(str(handle or "")) >= 2
        weight = 2 if claimed else 1
        nick_hash = js_fnv(clean_nick(nick).lower())
        when = (now_ms() if ts is None else ts) & 0xFFFFFFFF
        async with self.lock:
            db = self.conn
            db.execute("BEGIN IMMEDIATE")
            try:
                old = db.execute("SELECT slot, direction, weight FROM ballots WHERE feat = ? AND nick_hash = ?", (f, nick_hash)).fetchone()
                if old and int(old["direction"]) == dir_v and int(old["weight"]) == weight:
                    db.execute("COMMIT")
                    return {"ok": True, "dup": True, "feat": feat}
                if old:
                    slot = int(old["slot"])
                    db.execute("DELETE FROM ballots WHERE slot = ?", (slot,))
                else:
                    occupied = {int(r["slot"]) for r in db.execute("SELECT slot FROM ballots").fetchall()}
                    if len(occupied) >= MAX_BALLOTS:
                        db.execute("ROLLBACK")
                        return {"ok": False, "err": "ballot-full"}
                    slot = next(i for i in range(MAX_BALLOTS) if i not in occupied)
                db.execute("INSERT INTO ballots(slot,feat,direction,weight,donor,nick_hash,net3,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                           (slot, f, dir_v, weight, 1 if claimed else 0, nick_hash, net3 & 0xFFFFFFFF, when))
                counts = db.execute("SELECT direction, SUM(weight) AS total FROM ballots WHERE feat = ? GROUP BY direction", (f,)).fetchall()
                totals = {int(r["direction"]): int(r["total"]) for r in counts}
                db.execute("COMMIT")
                return {"ok": True, "feat": feat, "yes": totals.get(1, 0), "no": totals.get(0, 0), "weight": weight}
            except Exception:
                db.execute("ROLLBACK")
                raise


@dataclass(eq=False)
class Peer:
    socket: WebSocket
    room: str = ""
    nick: str = "molty"
    last_edit: int = 0
    last_save: int = 0
    last_vote: int = 0


class Hub:
    def __init__(self, store: Store):
        self.store = store
        self.peers: set[Peer] = set()
        self.lock = asyncio.Lock()

    def who_in(self, room: str) -> list[str]:
        return [p.nick for p in self.peers if p.room == room]

    async def publish(self, room: str, data: dict):
        # Copy peer list before awaiting network I/O; failed clients are cleaned up.
        recipients = [p for p in tuple(self.peers) if p.room == room]
        for p in recipients:
            try:
                await p.socket.send_json(data)
            except Exception:
                self.peers.discard(p)

    async def join(self, peer: Peer, room_value: Any, nick_value: Any):
        room = clean_room(room_value)
        nick = clean_nick(nick_value)
        if not room:
            await peer.socket.send_json({"t": "error", "err": "bad-room"})
            return
        peer.room = room
        peer.nick = nick
        doc = await self.store.load(room)
        await peer.socket.send_json({"t": "doc", "room": room, "html": doc["html"], "updatedAt": doc["updatedAt"], "who": self.who_in(room)})
        await self.publish(room, {"t": "presence", "event": "join", "nick": nick, "who": self.who_in(room)})

    async def leave(self, peer: Peer):
        old = peer.room
        if not old:
            return
        # Source removes every matching nickname from the reported roster,
        # including separately connected peers claiming the same nickname.
        who = [nick for nick in self.who_in(old) if nick != peer.nick]
        peer.room = ""
        # The original deletes the membership record on leave; a later join
        # recreates it with fresh per-connection throttling counters.
        peer.last_edit = 0
        peer.last_save = 0
        peer.last_vote = 0
        await self.publish(old, {"t": "presence", "event": "leave", "nick": peer.nick, "who": who})

    async def rpc(self, peer: Peer, method: Any, data: Any) -> dict:
        if not isinstance(data, dict):
            return {"ok": False, "err": "bad-args"}
        if method == "rooms":
            return await self.store.rooms()
        if method == "load":
            return await self.store.load(data.get("room"))
        if method == "who":
            room = clean_room(data.get("room"))
            return {"ok": True, "room": room, "who": self.who_in(room)} if room else {"ok": False, "err": "bad-room"}
        if method == "save":
            # Mirror original: the throttle is per connected socket and occurs
            # after validation but before the write.
            room, html = clean_room(data.get("room")), str(data.get("html") or "")
            if not room:
                return {"ok": False, "err": "bad-room"}
            if utf16_length(html) > MAX_DOC_UNITS:
                return {"ok": False, "err": "too-big", "max": MAX_DOC_UNITS}
            now = now_ms()
            if now - peer.last_save < SAVE_COOLDOWN_MS:
                return {"ok": False, "err": "slow-down"}
            peer.last_save = now
            result = await self.store.save(room, html, now)
            if result["ok"]:
                await self.publish(room, {"t": "saved", "nick": peer.nick, "room": room})
            return result
        if method == "features":
            return await self.store.features()
        if method == "vote":
            feat = data.get("feat")
            if feat not in FEATURES:
                return {"ok": False, "err": "bad-feature"}
            now = now_ms() & 0xFFFFFFFF
            if now - peer.last_vote < VOTE_COOLDOWN_MS:
                return {"ok": False, "err": "slow-down"}
            result = await self.store.vote(feat, data.get("dir"), data.get("nick"), data.get("donor"), data.get("handle"))
            if result["ok"] and not result.get("dup"):
                peer.last_vote = now
            elif result["ok"]:
                # Original updates lastVote even for a duplicate only if it did
                # not return early; duplicate votes have no cooldown update.
                pass
            return result
        return {"ok": False, "err": "unknown-rpc", "method": method}


def _loopback_peer(host: str) -> bool:
    # TestClient uses an in-process ASGI peer named testclient; real sockets report IPs.
    if host == "testclient":
        return True
    from ipaddress import ip_address
    try:
        return ip_address(host).is_loopback
    except (ValueError, TypeError):
        return False


def create_app(db_path: str | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        from services.workflows import scheduler_loop
        task = asyncio.create_task(scheduler_loop())
        try:
            yield
        finally:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
    service = FastAPI(title="Canceled Agent — Independent Room & Vote Service", version="0.4.0", lifespan=lifespan)
    store = Store(db_path or os.getenv("AGENT_DB_PATH", "./agent-rooms.db"))
    hub = Hub(store)
    @service.middleware("http")
    async def optional_bearer_auth(request: Request, call_next):
        # Protect every route, including OpenAI/Anthropic-compatible /v1 endpoints.
        # Only /health remains intentionally public; local no-token development still works.
        if request.url.path == "/health":
            return await call_next(request)
        token = os.getenv("AGENT_BACKEND_TOKEN", "").strip()
        if token:
            import hmac
            auth = request.headers.get("authorization", "")
            if not hmac.compare_digest(auth, "Bearer " + token):
                return JSONResponse({"ok": False, "err": "unauthorized"}, status_code=401)
        elif not _loopback_peer(request.client.host if request.client else ""):
            return JSONResponse({"ok": False, "err": "backend-token-required-for-network"}, status_code=403)
        return await call_next(request)
    from services.mesh import mount as mount_mesh
    from services.platform import mount as mount_platform
    from services.inference import router as ai_router
    from services.workflows import mount as mount_workflows
    from services.bridge_api import router as bridge_router
    from services.moltbook_route import router as molt_router
    from services.moltbook_ops import router as molt_ops_router
    from services.autonomy_executor import router as autonomy_router
    service.include_router(mount_mesh(store))
    service.include_router(mount_platform(store))
    service.include_router(ai_router)
    service.include_router(mount_workflows(store))
    service.include_router(bridge_router)
    service.include_router(molt_router)
    service.include_router(molt_ops_router)
    service.include_router(autonomy_router)
    service.state.store = store
    service.state.hub = hub

    @service.get("/health")
    async def health():
        return {"ok": True, "service": "rooms-votes", "perchanceDependency": False}

    @service.get("/api/rooms")
    async def list_rooms():
        return await store.rooms()

    @service.get("/api/rooms/{room}")
    async def load_room(room: str):
        return await store.load(room)

    @service.put("/api/rooms/{room}")
    async def save_room(room: str, body: dict):
        """REST writer; regular per-connection write throttle is on /ws RPC."""
        return await store.save(room, body.get("html"))

    @service.get("/api/votes")
    async def list_votes():
        return await store.features()

    @service.post("/api/votes")
    async def cast_vote(body: dict):
        return await store.vote(body.get("feat"), body.get("dir"), body.get("nick"), body.get("donor"), body.get("handle"))

    @service.websocket("/ws")
    async def websocket(socket: WebSocket):
        token = os.getenv("AGENT_BACKEND_TOKEN", "").strip()
        if token:
            import hmac
            if not hmac.compare_digest(socket.headers.get("authorization", ""), "Bearer " + token):
                await socket.close(code=4401)
                return
        elif not _loopback_peer(socket.client.host if socket.client else ""):
            await socket.close(code=4403)
            return
        await socket.accept()
        peer = Peer(socket)
        hub.peers.add(peer)
        try:
            while True:
                msg = await socket.receive_json()
                if not isinstance(msg, dict) or not isinstance(msg.get("t"), str):
                    continue
                kind = msg["t"]
                if kind == "join":
                    await hub.join(peer, msg.get("room"), msg.get("nick"))
                elif kind == "leave":
                    await hub.leave(peer)
                elif kind == "edit" and peer.room:
                    now = now_ms()
                    if now - peer.last_edit < EDIT_COOLDOWN_MS:
                        continue
                    html = str(msg.get("html") or "")
                    if utf16_length(html) > MAX_DOC_UNITS:
                        continue
                    peer.last_edit = now
                    await hub.publish(peer.room, {"t": "edit", "nick": peer.nick, "mid": str(msg.get("mid") or ""), "html": html})
                elif kind == "rpc":
                    arg = msg.get("data")
                    if isinstance(arg, str):
                        try:
                            arg = json.loads(arg)
                        except ValueError:
                            arg = None
                    result = await hub.rpc(peer, msg.get("method"), arg)
                    await socket.send_json({"t": "rpc_result", "requestId": msg.get("requestId"), "result": result})
        except (WebSocketDisconnect, ValueError):
            pass
        finally:
            await hub.leave(peer)
            hub.peers.discard(peer)

    return service


app = create_app()
