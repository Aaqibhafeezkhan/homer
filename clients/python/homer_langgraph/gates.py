"""Pure interrupt gate helpers for Homer LangGraph workflows.

These helpers are deliberately **pure**: they only call LangGraph's
``interrupt()`` to park the graph and surface a payload. They perform **no**
side effects of their own. The side effect a gate implies — asking the
operator a question, fanning out to a sub-agent — is performed exactly once
by the runtime (:mod:`homer_langgraph.runner`) *after* ``invoke()`` returns
with the pending interrupt.

This split matters because Homer resumes a parked thread by re-running the
agent from the top. LangGraph re-executes the interrupted node from its start
up to ``interrupt()``; any side effect placed in the node body would fire
again on every resume. Keeping the gates pure guarantees the ask/fan-out
happens once.
"""

from __future__ import annotations

import json
import sys
from typing import Any

from langgraph.types import interrupt

from .results import homer_fetch_result

# Hard client-side ceiling on both retry budgets. A retry re-dispatches a
# sub-agent, so the bound is local and explicit at the point of use — never
# taken from the caller's value alone (ADR-0047 rule 4).
MAX_FANOUT_RETRIES = 5

# Sub statuses that end the fan-out: the sub is done either way. A KILLED sub is
# a decision (an operator killed it), not a failure to re-dispatch.
_FINAL_STATUSES = ("FINISHED", "KILLED")
# The one terminal status worth re-dispatching: the sub ran and failed.
_FAILED_STATUS = "FAILED"


def homer_interrupt(
    text: str,
    *,
    options: list[str] | None = None,
    ttl: int | None = None,
) -> str:
    """Park the graph on a human gate and return the operator's answer.

    Surfaces a ``{"type": "human", ...}`` interrupt payload. The runtime turns
    this into a ``homer-ask ask-and-dispatch`` call; when the operator answers,
    Homer resumes the thread and this call returns the answer string.

    Args:
        text: Question text (Markdown) shown to the operator.
        options: Optional list of suggested answer options.
        ttl: Optional time-to-live in seconds for the question.

    Returns:
        The operator's answer string supplied as the resume value.
    """
    return interrupt(
        {
            "type": "human",
            "text": text,
            "options": options or [],
            "ttl": ttl,
        }
    )


def _validate_retries(name: str, value: Any) -> None:
    """Reject a retry budget before any ``interrupt()`` is called.

    ``bool`` is rejected explicitly: it is an ``int`` in Python, and ``True``
    silently meaning "one retry" hides a caller's mistake.
    """
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(
            f"homer_fanout: {name} must be an int, got {type(value).__name__}"
        )
    if not 0 <= value <= MAX_FANOUT_RETRIES:
        raise ValueError(
            f"homer_fanout: {name} must be between 0 and {MAX_FANOUT_RETRIES}, got {value}"
        )


def _decode_resume(raw: Any) -> dict[str, Any]:
    """Decode a raw resume value into a fan-out payload dict.

    The runtime feeds the raw ``HMR_LG_RESUME`` string back via
    ``Command(resume=...)``, so for a fan-out that string is a JSON object;
    a caller already handing back a dict (e.g. in tests) is passed through.
    Anything else — malformed JSON, or JSON that is not an object — raises
    ``ValueError``: the caller classifies it as an infrastructure outcome,
    never as a success.
    """
    try:
        payload = raw if isinstance(raw, dict) else json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise ValueError("fan-out resume payload is not decodable JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError("fan-out resume payload is not a JSON object")
    return payload


def _resolve_result(payload: dict[str, Any]) -> dict[str, Any]:
    """Splice a by-reference result into the payload, when there is one.

    Over-cap result: the value was dropped in favor of a capability token.
    Fetch it by reference (idempotent GET) and present a normal payload.
    """
    if payload.get("result") is None and payload.get("resultRef"):
        try:
            payload["result"] = homer_fetch_result(payload["resultRef"])
        except RuntimeError as exc:
            # A sub that did not FINISH broke on its last command, so the artifact
            # behind the ref is that command's output (often a multi-MB Claude
            # NDJSON stream Homer refuses to transform). Its result is not what
            # the graph needs anyway: the graph's own status check must get to
            # run, so keep ``result`` null and report instead of crashing.
            if payload.get("status") == "FINISHED":
                raise
            print(f"homer_fanout: {exc}; sub ended {payload.get('status')}, "
                  f"resuming with result=null", file=sys.stderr)
    return payload


def _retry_kind(payload: dict[str, Any]) -> str:
    """Which retry budget a payload draws on: ``"failed"``, ``"infra"`` or ``""``.

    Anything that is not a terminal status is an infrastructure outcome — an
    ``UNKNOWN`` sub, a runner lost mid-run, a missing or unrecognized status —
    so a broken runner cannot spend the failure budget, and an unknown status is
    never read as success.
    """
    status = payload.get("status")
    if status in _FINAL_STATUSES:
        return ""
    return "failed" if status == _FAILED_STATUS else "infra"


def _log_retry(agent: str, attempt: int, limit: int, status: Any) -> None:
    """Report one retry on stderr — agent, attempt and status only.

    Never the params, the result, the processId or a resultRef token: the sub's
    caller supplied the first and the sub returned the rest.
    """
    print(
        f"homer_fanout: retrying {agent} attempt {attempt}/{limit} after {status}",
        file=sys.stderr,
    )


def homer_fanout(
    agent: str,
    *,
    params: dict[str, Any] | None = None,
    result_jsonpath: str | None = None,
    ttl: int | None = None,
    group: str | None = None,
    retries: int = 0,
    infra_retries: int = 0,
) -> dict[str, Any]:
    """Park the graph on a fan-out gate and return the sub-process result.

    Surfaces a ``{"type": "fanout", ...}`` interrupt payload. The runtime turns
    this into a ``homer-dispatch`` call with completion-continuation flags;
    when the dispatched sub reaches a terminal state, Homer resumes the thread
    with the JSON resume payload ``{status, processId, result}``.

    Args:
        agent: Name of the sub-agent to dispatch.
        params: Optional query params (``key=value``) for the sub-agent run.
        result_jsonpath: Optional JsonPath applied to the sub's stdout; the
            extracted value becomes the ``result`` field of the resume payload.
        ttl: Optional continuation expiry in seconds.
        group: Optional fan-out group name. When two or more fanout interrupts
            parked in the same segment share a non-None ``group``, the runtime
            dispatches them all at once and resumes the graph **once** after the
            last finishes (``homer-join``), instead of one resume per sub. Leave
            it ``None`` (default) for today's sequential, one-resume-per-sub
            path. The decoded per-branch payload is identical in both modes. See
            ADR-0026. Node bodies re-run on resume, so a grouped fan-out node
            must keep its body idempotent (avoid additive reducers over the
            fan-out result).
        retries: How many times to re-dispatch this sub after it comes back
            ``FAILED``, at most ``MAX_FANOUT_RETRIES``. Each attempt parks this
            node again with a fresh fan-out interrupt, so the runtime dispatches
            the sub once more; the graph then continues with the payload of the
            last attempt. ``0`` (default) keeps today's behaviour — the failure
            is returned as it arrives. The node body must be idempotent: it
            re-runs from its start on every attempt. See README.md "Retrying
            failed fan-outs".
        infra_retries: The same, for infrastructure outcomes: ``UNKNOWN``, a
            missing or unrecognized status, or an undecodable resume payload.
            Kept separate from ``retries`` so a broken runner cannot spend the
            failure budget. A payload that is still undecodable once this budget
            is spent raises, exactly as it does with no retries at all.

    Returns:
        The decoded resume payload dict with ``status``, ``processId`` and
        ``result`` keys. When the sub's result exceeded Homer's inline cap the
        payload arrives with ``result`` null and a ``resultRef``; the full
        result is fetched by reference and spliced back into ``result``.
    """
    _validate_retries("retries", retries)
    _validate_retries("infra_retries", infra_retries)
    park: dict[str, Any] = {
        "type": "fanout",
        "agent": agent,
        "params": params or {},
        "result_jsonpath": result_jsonpath,
        "ttl": ttl,
    }
    # Only present the key when grouped, so an ungrouped fan-out payload stays
    # byte-for-byte identical to the pre-join sequential form.
    if group is not None:
        park["group"] = group
    # Every retry re-parks with this same payload: the sub is re-dispatched from
    # the original call's args, never from the failed result.
    # The budgets are loop-local, and the loop index is the attempt count:
    # LangGraph re-runs this node from its start and replays the thread's own
    # recorded resume values in order, so the counters rebuild exactly on every
    # segment. No state key, no side file — and graph state cannot raise them.
    failed_left, infra_left = retries, infra_retries
    while True:
        raw = interrupt(park)
        try:
            result = _decode_resume(raw)
        except ValueError:
            if infra_left <= 0:
                raise
            infra_left -= 1
            _log_retry(agent, infra_retries - infra_left, infra_retries, "malformed payload")
            continue
        kind = _retry_kind(result)
        if kind == "failed" and failed_left > 0:
            failed_left -= 1
            _log_retry(agent, retries - failed_left, retries, result.get("status"))
            continue
        if kind == "infra" and infra_left > 0:
            infra_left -= 1
            _log_retry(
                agent, infra_retries - infra_left, infra_retries,
                result.get("status") or "no status",
            )
            continue
        return _resolve_result(result)
