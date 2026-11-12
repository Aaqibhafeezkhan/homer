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


def _scripted_interrupt(monkeypatch, values):
    """Patch ``interrupt`` to return ``values`` in order, recording its payloads."""
    calls = []
    scripted = iter(values)

    def fake_interrupt(payload):
        calls.append(payload)
        return next(scripted)

    monkeypatch.setattr(gates, "interrupt", fake_interrupt)
    return calls


def _failed(process_id=7, **extra):
    return json.dumps({"status": "FAILED", "processId": process_id, "result": None, **extra})


FINISHED_PAYLOAD = {"status": "FINISHED", "processId": 8, "result": "ok"}


@pytest.mark.parametrize(
    "kwargs",
    [
        {"retries": -1},
        {"retries": True},
        {"retries": 1.0},
        {"retries": "2"},
        {"retries": gates.MAX_FANOUT_RETRIES + 1},
        {"infra_retries": -1},
        {"infra_retries": False},
        {"infra_retries": gates.MAX_FANOUT_RETRIES + 1},
    ],
)
def test_homer_fanout_rejects_invalid_retry_budgets(monkeypatch, kwargs):
    # A bad budget is refused before anything is parked, so no interrupt payload
    # ever reaches the runtime.
    def fail_interrupt(payload):
        raise AssertionError("must not interrupt with an invalid retry budget")

    monkeypatch.setattr(gates, "interrupt", fail_interrupt)

    with pytest.raises(ValueError, match="homer_fanout"):
        gates.homer_fanout("worker", **kwargs)


def test_homer_fanout_accepts_the_hard_maximum(monkeypatch):
    _scripted_interrupt(monkeypatch, [json.dumps(FINISHED_PAYLOAD)])

    assert gates.homer_fanout("worker", retries=gates.MAX_FANOUT_RETRIES) == FINISHED_PAYLOAD


@pytest.mark.parametrize(
    "status,retried",
    [
        ("FAILED", True),
        ("UNKNOWN", True),
        (None, True),
        ("WORKING", True),
        ("FINISHED", False),
        ("KILLED", False),
    ],
)
def test_homer_fanout_status_classification(monkeypatch, status, retried):
    # FINISHED/KILLED end the fan-out; FAILED draws on `retries`; every other
    # outcome — UNKNOWN, a missing status, an unrecognized one — is
    # infrastructure, so it can never be read as success.
    first = {"processId": 7, "result": None}
    if status is not None:
        first["status"] = status
    calls = _scripted_interrupt(
        monkeypatch, [json.dumps(first), json.dumps(FINISHED_PAYLOAD)]
    )

    result = gates.homer_fanout("worker", retries=1, infra_retries=1)

    assert len(calls) == (2 if retried else 1)
    assert result == (FINISHED_PAYLOAD if retried else first)


def test_homer_fanout_default_budgets_keep_todays_behaviour(monkeypatch, capsys):
    # retries=0 / infra_retries=0: the failure is returned as it arrives, with no
    # second interrupt and no retry line.
    calls = _scripted_interrupt(monkeypatch, [_failed()])

    result = gates.homer_fanout("worker")

    assert len(calls) == 1
    assert result["status"] == "FAILED"
    assert capsys.readouterr().err == ""


def test_homer_fanout_retry_reparks_with_the_original_payload(monkeypatch):
    # The re-sent payload is the original call's args — never the failed result.
    calls = _scripted_interrupt(
        monkeypatch, [_failed(), json.dumps(FINISHED_PAYLOAD)]
    )

    gates.homer_fanout(
        "worker",
        params={"topic": "pricing"},
        result_jsonpath="$.summary",
        ttl=3600,
        group="designs",
        retries=1,
    )

    assert calls[0] == calls[1]
    assert calls[1] == {
        "type": "fanout",
        "agent": "worker",
        "params": {"topic": "pricing"},
        "result_jsonpath": "$.summary",
        "ttl": 3600,
        "group": "designs",
    }


def test_homer_fanout_retry_line_carries_no_params_or_token(monkeypatch, capsys):
    secret = "s3cr3t-api-key"
    _scripted_interrupt(
        monkeypatch,
        [
            _failed(
                resultRef={
                    "processId": 7,
                    "path": "cmd_0.out",
                    "token": "ref-token-abc",
                    "sizeBytes": 99,
                }
            ),
            json.dumps(FINISHED_PAYLOAD),
        ],
    )

    result = gates.homer_fanout("worker", params={"apiKey": secret}, retries=1)

    assert result == FINISHED_PAYLOAD
    err = capsys.readouterr().err
    assert "homer_fanout: retrying worker attempt 1/1 after FAILED" in err
    assert secret not in err
    assert "ref-token-abc" not in err
    assert "processId" not in err


def test_homer_fanout_retry_does_not_fetch_a_discarded_result(monkeypatch):
    # A retried payload's result is dropped, so its resultRef is never redeemed —
    # the retry re-dispatches the sub, it does not read the failed attempt.
    _scripted_interrupt(
        monkeypatch,
        [
            _failed(resultRef={"processId": 7, "path": "cmd_0.out", "token": "t", "sizeBytes": 9}),
            json.dumps(FINISHED_PAYLOAD),
        ],
    )

    def fail_fetch(ref):
        raise AssertionError("must not fetch the result of a retried payload")

    monkeypatch.setattr(gates, "homer_fetch_result", fail_fetch)

    assert gates.homer_fanout("worker", retries=1) == FINISHED_PAYLOAD


def test_homer_fanout_returns_the_last_payload_when_failed_budget_is_spent(monkeypatch):
    # retries=1: one re-dispatch, then the second FAILED reaches the graph as-is.
    calls = _scripted_interrupt(monkeypatch, [_failed(1), _failed(2)])

    result = gates.homer_fanout("worker", retries=1)

    assert len(calls) == 2
    assert result == {"status": "FAILED", "processId": 2, "result": None}


def test_homer_fanout_infra_budget_is_separate_from_failed(monkeypatch):
    # An UNKNOWN attempt spends the infra budget, a FAILED one the failure budget.
    unknown = json.dumps({"status": "UNKNOWN", "processId": 3, "result": None})
    calls = _scripted_interrupt(
        monkeypatch, [unknown, _failed(), json.dumps(FINISHED_PAYLOAD)]
    )

    result = gates.homer_fanout("worker", retries=1, infra_retries=1)

    assert len(calls) == 3
    assert result == FINISHED_PAYLOAD


def test_homer_fanout_infra_budget_does_not_retry_a_failure(monkeypatch):
    calls = _scripted_interrupt(monkeypatch, [_failed()])

    result = gates.homer_fanout("worker", retries=0, infra_retries=1)

    assert len(calls) == 1
    assert result["status"] == "FAILED"


def test_homer_fanout_failed_budget_does_not_retry_an_infra_outcome(monkeypatch):
    unknown = json.dumps({"status": "UNKNOWN", "processId": 3, "result": None})
    calls = _scripted_interrupt(monkeypatch, [unknown])

    result = gates.homer_fanout("worker", retries=1, infra_retries=0)

    assert len(calls) == 1
    assert result["status"] == "UNKNOWN"


@pytest.mark.parametrize("raw", ["{not json", "[]", '"a string"', "null"])
def test_homer_fanout_raises_on_a_malformed_payload_with_no_infra_budget(monkeypatch, raw):
    # Today's behaviour, kept as the default: an undecodable payload raises.
    calls = _scripted_interrupt(monkeypatch, [raw])

    with pytest.raises(ValueError):
        gates.homer_fanout("worker")

    assert len(calls) == 1


def test_homer_fanout_retries_a_malformed_payload_up_to_the_infra_budget(monkeypatch, capsys):
    calls = _scripted_interrupt(
        monkeypatch, ["{not json", "{still not json"]
    )

    with pytest.raises(ValueError):
        gates.homer_fanout("worker", infra_retries=1)

    assert len(calls) == 2
    err = capsys.readouterr().err
    assert "homer_fanout: retrying worker attempt 1/1 after malformed payload" in err
    assert "not json" not in err
