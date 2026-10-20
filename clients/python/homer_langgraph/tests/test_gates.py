"""Pure-gate payload tests: patch ``interrupt`` to capture its argument."""

from __future__ import annotations

import json

import pytest

from homer_langgraph import gates


def test_homer_interrupt_payload(monkeypatch):
    captured = {}

    def fake_interrupt(payload):
        captured["payload"] = payload
        return "the answer"

    monkeypatch.setattr(gates, "interrupt", fake_interrupt)

    result = gates.homer_interrupt("Proceed?", options=["yes", "no"], ttl=120)

    assert result == "the answer"
    assert captured["payload"] == {
        "type": "human",
        "text": "Proceed?",
        "options": ["yes", "no"],
        "ttl": 120,
    }


def test_homer_interrupt_defaults(monkeypatch):
    captured = {}
    monkeypatch.setattr(
        gates, "interrupt", lambda p: captured.setdefault("payload", p) or "ok"
    )

    gates.homer_interrupt("Q?")

    assert captured["payload"] == {
        "type": "human",
        "text": "Q?",
        "options": [],
        "ttl": None,
    }


def test_homer_fanout_payload_and_decode(monkeypatch):
    captured = {}
    resume = json.dumps({"status": "FINISHED", "processId": 42, "result": {"sum": "ok"}})

    def fake_interrupt(payload):
        captured["payload"] = payload
        return resume

    monkeypatch.setattr(gates, "interrupt", fake_interrupt)

    result = gates.homer_fanout(
        "worker",
        params={"topic": "pricing"},
        result_jsonpath="$.summary",
        ttl=3600,
    )

    assert captured["payload"] == {
        "type": "fanout",
        "agent": "worker",
        "params": {"topic": "pricing"},
        "result_jsonpath": "$.summary",
        "ttl": 3600,
    }
    assert result == {"status": "FINISHED", "processId": 42, "result": {"sum": "ok"}}


def test_homer_fanout_omits_group_when_none(monkeypatch):
    # Ungrouped fan-out payload must stay byte-for-byte the sequential form: no
    # "group" key at all.
    captured = {}
    monkeypatch.setattr(
        gates, "interrupt", lambda p: captured.setdefault("payload", p) or "{}"
    )

    gates.homer_fanout("worker")

    assert "group" not in captured["payload"]


def test_homer_fanout_includes_group_when_set(monkeypatch):
    captured = {}
    monkeypatch.setattr(
        gates, "interrupt", lambda p: captured.setdefault("payload", p) or "{}"
    )

    gates.homer_fanout("worker", group="architects")

    assert captured["payload"]["group"] == "architects"
    assert captured["payload"]["type"] == "fanout"


def test_homer_fanout_accepts_dict_resume(monkeypatch):
    payload = {"status": "FINISHED", "processId": 7, "result": None}
    monkeypatch.setattr(gates, "interrupt", lambda p: payload)

    # No resultRef -> no fetch, payload returned unchanged.
    assert gates.homer_fanout("worker") == payload


def test_homer_fanout_auto_fetches_when_result_null_and_ref_present(monkeypatch):
    resume = json.dumps(
        {
            "status": "FINISHED",
            "processId": 42,
            "result": None,
            "resultRef": {"processId": 42, "path": "cmd_0.out", "token": "tok-7", "sizeBytes": 99},
        }
    )
    monkeypatch.setattr(gates, "interrupt", lambda p: resume)
    fetched = {}

    def fake_fetch(ref):
        fetched["ref"] = ref
        return "THE BIG RESULT"

    monkeypatch.setattr(gates, "homer_fetch_result", fake_fetch)

    result = gates.homer_fanout("worker")

    assert fetched["ref"]["token"] == "tok-7"
    assert result["result"] == "THE BIG RESULT"


def test_homer_fanout_does_not_fetch_when_result_is_inline(monkeypatch):
    resume = json.dumps({"status": "FINISHED", "processId": 42, "result": "inline"})
    monkeypatch.setattr(gates, "interrupt", lambda p: resume)

    def fail_fetch(ref):
        raise AssertionError("must not fetch when the result is inline")

    monkeypatch.setattr(gates, "homer_fetch_result", fail_fetch)

    assert gates.homer_fanout("worker")["result"] == "inline"


def test_homer_fanout_keeps_result_none_when_fetch_fails_for_a_non_finished_sub(monkeypatch, capsys):
    # A FAILED sub's last command is the one that broke (e.g. a claude step whose
    # multi-MB NDJSON stdout is over the transform cap), so its resultRef may be
    # unredeemable. The graph's own status check must still get to run.
    resume = json.dumps(
        {
            "status": "FAILED",
            "processId": 4089,
            "result": None,
            "resultRef": {"processId": 4089, "path": "cmd_1.out", "token": "tok-7", "sizeBytes": 7920257},
        }
    )
    monkeypatch.setattr(gates, "interrupt", lambda p: resume)

    def fail_fetch(ref):
        raise RuntimeError("homer-result failed (exit 2) fetching resultRef for process 4089 path 'cmd_1.out'")

    monkeypatch.setattr(gates, "homer_fetch_result", fail_fetch)

    result = gates.homer_fanout("worker")

    assert result["status"] == "FAILED"
    assert result["processId"] == 4089
    assert result["result"] is None
    assert "4089" in capsys.readouterr().err


def test_homer_fanout_reraises_fetch_failure_for_a_finished_sub(monkeypatch):
    resume = json.dumps(
        {
            "status": "FINISHED",
            "processId": 42,
            "result": None,
            "resultRef": {"processId": 42, "path": "cmd_0.out", "token": "tok-7", "sizeBytes": 99},
        }
    )
    monkeypatch.setattr(gates, "interrupt", lambda p: resume)

    def fail_fetch(ref):
        raise RuntimeError("homer-result failed (exit 2) fetching resultRef for process 42 path 'cmd_0.out'")

    monkeypatch.setattr(gates, "homer_fetch_result", fail_fetch)

    with pytest.raises(RuntimeError, match="process 42"):
        gates.homer_fanout("worker")
