"""Host security regressions for all HTTP API namespaces and WebSocket entry points."""
import asyncio
import pytest
import httpx
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from services.rooms_votes.app import create_app, _loopback_peer


@pytest.fixture
def app(tmp_path):
    return create_app(str(tmp_path / "auth-regression.sqlite3"))


@pytest.mark.parametrize("endpoint,method", [
    ("/api/rooms", "get"),
    ("/v1/models", "get"),
    ("/v1/chat/completions", "post"),
    ("/v1/messages", "post"),
])
def test_configured_token_covers_every_namespace(app, monkeypatch, endpoint, method):
    monkeypatch.setenv("AGENT_BACKEND_TOKEN", "test-only-access-secret")
    with TestClient(app) as client:
        kwargs = {"json": {}} if method == "post" else {}
        assert client.request(method.upper(), endpoint, **kwargs).status_code == 401
        assert client.request(method.upper(), endpoint, headers={"Authorization": "Bearer incorrect"}, **kwargs).status_code == 401
        response = client.request(method.upper(), endpoint, headers={"Authorization": "Bearer test-only-access-secret"}, **kwargs)
        assert response.status_code != 401


def test_health_is_public_without_disclosing_state(app, monkeypatch):
    monkeypatch.setenv("AGENT_BACKEND_TOKEN", "test-only-access-secret")
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["ok"] is True
        assert "token" not in str(health.json()).lower()


def test_no_token_blocks_real_remote_socket_even_without_proxy(app, monkeypatch):
    monkeypatch.delenv("AGENT_BACKEND_TOKEN", raising=False)
    async def remote_requests():
        transport = httpx.ASGITransport(app=app, client=("198.51.100.45", 4242))
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            for target in ("/api/rooms", "/v1/models", "/docs"):
                response = await client.get(target)
                assert response.status_code == 403, (target, response.status_code)
            assert (await client.get("/health")).status_code == 200
    asyncio.run(remote_requests())


def test_local_no_token_operation_preserved(app, monkeypatch):
    monkeypatch.delenv("AGENT_BACKEND_TOKEN", raising=False)
    with TestClient(app) as client:
        assert client.get("/api/rooms").status_code == 200
        assert client.get("/v1/models").status_code == 200


def test_websocket_rejects_wrong_token(app, monkeypatch):
    monkeypatch.setenv("AGENT_BACKEND_TOKEN", "test-only-access-secret")
    with TestClient(app) as client:
        with pytest.raises(WebSocketDisconnect) as rejected:
            with client.websocket_connect("/ws", headers={"Authorization": "Bearer incorrect"}):
                pass
        assert rejected.value.code == 4401
        with client.websocket_connect("/ws", headers={"Authorization": "Bearer test-only-access-secret"}) as socket:
            assert socket is not None


def test_testclient_transport_exception_cannot_be_used_on_a_real_listener():
    assert _loopback_peer("testclient", ("testserver", 80))
    assert not _loopback_peer("testclient", ("0.0.0.0", 8765))
    assert not _loopback_peer("testclient", None)
    assert _loopback_peer("127.0.0.1", ("0.0.0.0", 8765))
    assert not _loopback_peer("198.51.100.45", ("0.0.0.0", 8765))
