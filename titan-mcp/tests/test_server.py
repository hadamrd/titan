"""Unit tests for the MCP server runtime — auth, error propagation, transport."""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest
import respx

from titan_mcp.generator import generate_tools, load_schema
from titan_mcp.server import (
    TitanClientConfig,
    auth_error,
    base_url_error,
    invoke_tool,
)

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="module")
def tools_by_name():
    report = generate_tools(load_schema(FIXTURES / "five_shapes.json"))
    return {t.name: t for t in report.tools}


# ── Auth / config preconditions ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_missing_token_returns_structured_error_without_calling_http(
    tools_by_name,
) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token=None)
    # No respx route registered — any HTTP call would raise ConnectError.
    result = await invoke_tool(tools_by_name["things_list"], {}, config)
    assert result == auth_error()


@pytest.mark.asyncio
async def test_missing_base_url_returns_structured_error(tools_by_name) -> None:
    config = TitanClientConfig(base_url=None, token="tok")
    result = await invoke_tool(tools_by_name["things_list"], {}, config)
    assert result == base_url_error()


# ── HTTP error propagation ──────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_404_from_controller_becomes_structured_error(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock(assert_all_called=True) as mock:
        mock.get("https://titan.example/things/missing").mock(
            return_value=httpx.Response(404, json={"error": "not found"})
        )
        result = await invoke_tool(tools_by_name["things_get"], {"id": "missing"}, config)
    assert result["ok"] is False
    assert result["error"] == "http_error"
    assert result["status"] == 404
    assert result["json"] == {"error": "not found"}


@pytest.mark.asyncio
async def test_transport_error_returns_structured_error_not_raise(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock() as mock:
        mock.get("https://titan.example/things").mock(
            side_effect=httpx.ConnectError("dns boom")
        )
        result = await invoke_tool(tools_by_name["things_list"], {}, config)
    assert result["ok"] is False
    assert result["error"] == "transport"
    assert "dns boom" in result["message"]


# ── Path + query construction ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_with_path_param_substitutes_url_correctly(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock(assert_all_called=True) as mock:
        route = mock.get("https://titan.example/things/abc").mock(
            return_value=httpx.Response(200, json={"id": "abc"})
        )
        result = await invoke_tool(tools_by_name["things_get"], {"id": "abc"}, config)
    assert route.called
    assert result["ok"] is True
    assert result["json"] == {"id": "abc"}
    assert route.calls.last.request.headers["authorization"] == "Bearer tok"


@pytest.mark.asyncio
async def test_get_with_query_params_passes_them_through(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock(assert_all_called=True) as mock:
        route = mock.get("https://titan.example/things", params={"limit": "10"}).mock(
            return_value=httpx.Response(200, json=[])
        )
        result = await invoke_tool(tools_by_name["things_list"], {"limit": 10}, config)
    assert route.called
    assert result["ok"] is True


@pytest.mark.asyncio
async def test_post_with_body_threads_json(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock(assert_all_called=True) as mock:
        route = mock.post("https://titan.example/things").mock(
            return_value=httpx.Response(201, json={"id": "new"})
        )
        payload = {"body": {"name": "alpha"}}
        result = await invoke_tool(tools_by_name["things_create"], payload, config)
    assert route.called
    sent = route.calls.last.request
    assert sent.content == b'{"name": "alpha"}' or sent.content == b'{"name":"alpha"}'
    assert result["ok"] is True
    assert result["status"] == 201


@pytest.mark.asyncio
async def test_delete_with_path_param_uses_delete_verb(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock(assert_all_called=True) as mock:
        route = mock.delete("https://titan.example/things/abc").mock(
            return_value=httpx.Response(204)
        )
        result = await invoke_tool(tools_by_name["things_delete"], {"id": "abc"}, config)
    assert route.called
    assert result["ok"] is True
    assert result["status"] == 204


@pytest.mark.asyncio
async def test_path_param_values_are_url_encoded(tools_by_name) -> None:
    """A branch like ``feature/x`` must not silently extend the path."""
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    with respx.mock(assert_all_called=True) as mock:
        # %2F is the safe encoding for an in-segment slash.
        route = mock.get("https://titan.example/things/feature%2Fx").mock(
            return_value=httpx.Response(200, json={})
        )
        result = await invoke_tool(tools_by_name["things_get"], {"id": "feature/x"}, config)
    assert route.called
    assert result["ok"] is True


@pytest.mark.asyncio
async def test_missing_required_path_param_returns_structured_error(tools_by_name) -> None:
    config = TitanClientConfig(base_url="https://titan.example", token="tok")
    # No HTTP route registered — should never be called.
    result = await invoke_tool(tools_by_name["things_get"], {}, config)
    assert result["ok"] is False
    assert result["error"] == "missing_argument"
    assert result["argument"] == "id"


# ── Config-from-env wiring ──────────────────────────────────────────────────


def test_config_from_env_reads_both_vars_and_strips_trailing_slash() -> None:
    cfg = TitanClientConfig.from_env(
        {"TITAN_BASE_URL": "https://titan.example/", "TITAN_TOKEN": "tok"}
    )
    assert cfg.base_url == "https://titan.example"
    assert cfg.token == "tok"


def test_config_from_env_handles_missing_vars() -> None:
    cfg = TitanClientConfig.from_env({})
    assert cfg.base_url is None
    assert cfg.token is None
