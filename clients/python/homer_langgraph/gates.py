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


def homer_fanout(
    agent: str,
    *,
    params: dict[str, Any] | None = None,
    result_jsonpath: str | None = None,
    ttl: int | None = None,
    group: str | None = None,
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

    Returns:
        The decoded resume payload dict with ``status``, ``processId`` and
        ``result`` keys. When the sub's result exceeded Homer's inline cap the
        payload arrives with ``result`` null and a ``resultRef``; the full
        result is fetched by reference and spliced back into ``result``.
    """
    payload: dict[str, Any] = {
        "type": "fanout",
        "agent": agent,
        "params": params or {},
        "result_jsonpath": result_jsonpath,
        "ttl": ttl,
    }
    # Only present the key when grouped, so an ungrouped fan-out payload stays
    # byte-for-byte identical to the pre-join sequential form.
    if group is not None:
        payload["group"] = group
    raw = interrupt(payload)
    # The runtime feeds the raw HMR_LG_RESUME string back via Command(resume=...).
    # For a fan-out that string is a JSON object; decode it. Guard against a
    # caller already handing back a dict (e.g. in tests).
    payload = raw if isinstance(raw, dict) else json.loads(raw)
    # Over-cap result: the value was dropped in favor of a capability token.
    # Fetch it by reference (idempotent GET) and present a normal payload.
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
