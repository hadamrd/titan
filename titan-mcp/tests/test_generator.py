"""Unit tests for the OpenAPI → MCP tool generator."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from titan_mcp.generator import (
    SchemaError,
    generate_tools,
    load_schema,
)

FIXTURES = Path(__file__).parent / "fixtures"
BUNDLED = Path(__file__).resolve().parents[1] / "src" / "titan_mcp" / "schema" / "openapi.json"


# ── Happy path ───────────────────────────────────────────────────────────────


def test_five_shapes_produces_five_tools_with_right_names_and_signatures() -> None:
    schema = load_schema(FIXTURES / "five_shapes.json")
    report = generate_tools(schema)

    # The fixture documents exactly 5 operations across the 5 shapes the issue
    # calls out (GET-query, GET-path, POST-body, PATCH-body, DELETE-path).
    assert report.documented == 5
    assert len(report.tools) == 5
    assert not report.skipped, f"unexpected skips: {report.skipped}"

    by_name = {t.name: t for t in report.tools}
    assert set(by_name) == {
        "things_list",
        "things_create",
        "things_get",
        "things_update",
        "things_delete",
    }

    # GET /things — query param, no body.
    list_tool = by_name["things_list"]
    assert list_tool.method == "GET"
    assert list_tool.path == "/things"
    assert list_tool.query_params == ("limit",)
    assert list_tool.path_params == ()
    assert list_tool.body_param is None
    assert "limit" in list_tool.input_schema["properties"]
    assert list_tool.input_schema["required"] == []

    # GET /things/{id} — path param required.
    get_tool = by_name["things_get"]
    assert get_tool.path_params == ("id",)
    assert "id" in get_tool.input_schema["required"]

    # POST with required body → body in required list.
    create_tool = by_name["things_create"]
    assert create_tool.method == "POST"
    assert create_tool.body_param == "body"
    assert "body" in create_tool.input_schema["required"]

    # PATCH with path + body.
    update_tool = by_name["things_update"]
    assert update_tool.method == "PATCH"
    assert update_tool.body_param == "body"
    assert update_tool.path_params == ("id",)

    # DELETE with path param only.
    del_tool = by_name["things_delete"]
    assert del_tool.method == "DELETE"
    assert del_tool.body_param is None


def test_bundled_titan_snapshot_generates_tools_for_every_documented_operation() -> None:
    schema = load_schema(BUNDLED)
    report = generate_tools(schema)

    # The whole point of the generator: tool count must equal documented
    # operation count, otherwise the assistant silently loses coverage.
    assert report.documented > 0
    assert (
        len(report.tools) == report.documented
    ), f"lost operations: {report.summary()}"
    assert not report.skipped, f"snapshot regressed — skipped: {report.skipped}"

    # Snapshot should expose enough surface to be useful — 30+ is the target the
    # integration test guards on a live rig; we keep the same bar here so a
    # regression that strips the snapshot fails this test instead of CI later.
    assert len(report.tools) >= 30


# ── Adversarial / sad path ───────────────────────────────────────────────────


def test_duplicate_operation_id_raises_with_clear_message() -> None:
    schema = load_schema(FIXTURES / "duplicate_op.json")
    with pytest.raises(SchemaError) as excinfo:
        generate_tools(schema)
    msg = str(excinfo.value)
    assert "duplicate operationId" in msg
    assert "dup_op" in msg
    # Both colliding locations should be in the error so the operator can fix it.
    assert "/a" in msg and "/b" in msg


def test_missing_operation_id_is_skipped_not_raised() -> None:
    schema = {
        "openapi": "3.0.3",
        "paths": {"/x": {"get": {"responses": {"200": {"description": "ok"}}}}},
    }
    report = generate_tools(schema)
    assert report.tools == []
    assert len(report.skipped) == 1
    assert report.skipped[0].reason == "no operationId"


def test_path_template_references_undeclared_param_is_skipped() -> None:
    # The path mentions {id} but no parameter declares it — internally
    # inconsistent. Tool would build a broken URL; better to surface the gap.
    schema = {
        "openapi": "3.0.3",
        "paths": {
            "/x/{id}": {
                "get": {
                    "operationId": "bad",
                    "responses": {"200": {"description": "ok"}},
                }
            }
        },
    }
    report = generate_tools(schema)
    assert report.tools == []
    assert report.skipped and "undefined parameter" in report.skipped[0].reason


def test_load_schema_rejects_non_json(tmp_path: Path) -> None:
    p = tmp_path / "bad.json"
    p.write_text("not-json", encoding="utf-8")
    with pytest.raises(SchemaError):
        load_schema(p)


def test_load_schema_rejects_missing_paths_field(tmp_path: Path) -> None:
    p = tmp_path / "no_paths.json"
    p.write_text(json.dumps({"openapi": "3.0.3"}), encoding="utf-8")
    with pytest.raises(SchemaError):
        load_schema(p)


def test_path_level_params_are_inherited_by_each_operation() -> None:
    schema = {
        "openapi": "3.0.3",
        "paths": {
            "/x/{id}": {
                "parameters": [
                    {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}
                ],
                "get": {"operationId": "x_get", "responses": {"200": {"description": "ok"}}},
                "delete": {"operationId": "x_delete", "responses": {"200": {"description": "ok"}}},
            }
        },
    }
    report = generate_tools(schema)
    assert len(report.tools) == 2
    for tool in report.tools:
        assert tool.path_params == ("id",)
        assert "id" in tool.input_schema["required"]


def test_report_summary_lists_skipped_reasons() -> None:
    schema = {
        "openapi": "3.0.3",
        "paths": {
            "/x": {"get": {"responses": {"200": {"description": "ok"}}}},
            "/y": {"get": {"operationId": "y", "responses": {"200": {"description": "ok"}}}},
        },
    }
    report = generate_tools(schema)
    s = report.summary()
    assert "generated 1 tools from 2 operations" in s
    assert "skipped 1" in s
    assert "no operationId" in s
