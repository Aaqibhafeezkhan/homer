"""Read-by-reference fetch test: mock subprocess, assert argv and body."""

from __future__ import annotations

import subprocess
from types import SimpleNamespace

import pytest

from homer_langgraph import results


def test_homer_fetch_result_argv_and_body(monkeypatch):
    calls = {}

    def fake_run(argv, check, capture_output, text):
        calls["argv"] = argv
        calls["check"] = check
        return SimpleNamespace(stdout="the big result body")

    monkeypatch.setattr(subprocess, "run", fake_run)

    body = results.homer_fetch_result({"processId": 7, "path": "cmd_0.out", "token": "tok-9", "sizeBytes": 99})

    assert body == "the big result body"
    assert calls["check"] is True
    assert calls["argv"] == ["homer-result", "--token", "tok-9"]


def test_homer_fetch_result_returns_body_verbatim(monkeypatch):
    # No stripping: the (possibly transformed) body is returned as-is.
    monkeypatch.setattr(
        subprocess, "run", lambda *a, **k: SimpleNamespace(stdout="line1\nline2\n")
    )
    assert results.homer_fetch_result({"token": "t"}) == "line1\nline2\n"


def test_homer_fetch_result_error_does_not_leak_token(monkeypatch):
    # On a non-zero exit, the capability token (passed on argv) must not reach
    # the raised exception or its traceback — otherwise it lands in Homer's
    # process logs and is replayable within its TTL.
    def fake_run(argv, check, capture_output, text):
        raise subprocess.CalledProcessError(returncode=2, cmd=argv, stderr="boom")

    monkeypatch.setattr(subprocess, "run", fake_run)

    with pytest.raises(RuntimeError) as excinfo:
        results.homer_fetch_result(
            {"processId": 7, "path": "cmd_0.out", "token": "super-secret-tok", "sizeBytes": 99}
        )

    # Token absent from the sanitized message, and the chained CalledProcessError
    # (whose str() carries the token) is suppressed from the traceback.
    assert "super-secret-tok" not in str(excinfo.value)
    assert excinfo.value.__cause__ is None
    assert excinfo.value.__suppress_context__ is True
