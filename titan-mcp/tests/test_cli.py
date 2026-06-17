"""Smoke tests for the ``titan-mcp`` console script."""

from __future__ import annotations

from pathlib import Path

from titan_mcp import cli

FIXTURES = Path(__file__).parent / "fixtures"
BUNDLED = Path(__file__).resolve().parents[1] / "src" / "titan_mcp" / "schema" / "openapi.json"


def test_diagnose_prints_summary_and_exits_zero(capsys) -> None:
    rc = cli.main(["--schema", str(FIXTURES / "five_shapes.json"), "diagnose"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "generated 5 tools from 5 operations" in out
    assert "things_list" in out


def test_diagnose_against_bundled_snapshot_reports_n_equals_m(capsys) -> None:
    rc = cli.main(["--schema", str(BUNDLED), "diagnose"])
    out = capsys.readouterr().out
    assert rc == 0
    # The whole point: N == M for the shipped snapshot.
    import re

    match = re.search(r"generated (\d+) tools from (\d+) operations", out)
    assert match, out
    n, m = int(match.group(1)), int(match.group(2))
    assert n == m
    assert n >= 30


def test_diagnose_on_duplicate_schema_returns_nonzero(capsys) -> None:
    rc = cli.main(["--schema", str(FIXTURES / "duplicate_op.json"), "diagnose"])
    err = capsys.readouterr().err
    assert rc == 2
    assert "duplicate operationId" in err
