"""Run/resume loop for the Homer LangGraph runtime.

Homer launches this via ``python -m homer_langgraph run --module <module>``.
A single invocation handles one *segment* of a workflow: it runs (or resumes)
the graph until it either completes or parks on a pending ``interrupt()``.
When the graph parks, this runtime performs the side effect the gate implied
(ask the operator, fan out to a sub-agent) exactly once, then exits 0. The
side effect re-runs the agent later with a resume value in the environment,
which drives the next segment.

See ``README.md`` in this package for the durable-checkpoint + dispatch-resume
model and the env contract.
"""

from __future__ import annotations

import importlib
import json
import os
import sqlite3
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph.state import CompiledStateGraph, StateGraph
from langgraph.types import Command

# Env contract (see EnvConstants.kt on the Homer side).
ENV_THREAD = "HMR_LG_THREAD"
ENV_RESUME = "HMR_LG_RESUME"
ENV_STATE_DIR = "HOMER_LG_STATE_DIR"
ENV_AGENT_NAME = "HMR_AGENT_NAME"
ENV_INPUT = "HMR_LG_INPUT"
# EnvConstants.LG_INTERRUPT on the Homer side, which reserves the name and reads
# it to attribute sub-agent runs to their node: the stable id of the single
# interrupt this segment is resolving. Threaded back to the resumed agent (as a
# --param for the human gate, a --continue-param for fan-out) so the resume run
# can build a per-interrupt Command(resume={id: value}) map; absent → the
# back-compat scalar resume path. See README.md "Multi-interrupt resolution".
ENV_INTERRUPT = "HMR_LG_INTERRUPT"
ENV_ARTIFACTS_DIR = "HMR_ARTIFACTS_DIR"
ENV_RECOVER = "HMR_LG_RECOVER"  # recover marker (ADR-0044): set by Homer's resume endpoint

# The env name the runtime asks Homer to deliver the resume value under. The
# wrappers default to HMR_LG_RESUME; we pass it explicitly to stay in sync.
RESUME_PARAM = ENV_RESUME

ARTIFACT_FILENAME = "langgraph.json"
CHECKPOINT_FILENAME = "checkpoints.sqlite"

DEFAULT_GRAPH_ATTR = "graph"


def _load_graph(module_spec: str) -> Any:
    """Import the graph object named by ``pkg.mod:attr`` or ``pkg.mod``.

    Without an explicit ``:attr`` the attribute ``graph`` is used. The module
    is expected to expose either a compilable ``StateGraph`` builder or an
    already-compiled graph.
    """
    if ":" in module_spec:
        module_name, attr = module_spec.split(":", 1)
    else:
        module_name, attr = module_spec, DEFAULT_GRAPH_ATTR
    module = importlib.import_module(module_name)
    return getattr(module, attr)


def _compile(graph: Any, saver: SqliteSaver) -> CompiledStateGraph:
    """Return a compiled graph bound to ``saver``.

    Accepts an uncompiled ``StateGraph`` (compiles it with the checkpointer) or
    an already-compiled graph (used as-is — the module is then responsible for
    having attached a checkpointer). Anything else raises ``TypeError``.
    """
    if isinstance(graph, StateGraph):
        return graph.compile(checkpointer=saver)
    if not callable(getattr(graph, "invoke", None)):
        raise TypeError(f"{graph!r} is not a StateGraph or compiled graph")
    return graph


def _resume_payload(
    payload: dict[str, Any], thread_id: str, agent_name: str, interrupt_id: str
) -> list[str]:
    """Build the wrapper argv for a pending interrupt payload.

    ``human`` → ``homer-ask ask-and-dispatch`` (re-run the agent with the
    operator's answer). ``fanout`` → ``homer-dispatch`` with completion
    continuation flags (re-run the agent with the sub's result).

    ``interrupt_id`` is the stable id of the interrupt being resolved; it is
    threaded back so the resume run targets exactly this interrupt via a
    per-interrupt resume map. It must reach the *resumed agent*: for the human
    gate that agent is the dispatch target, so it rides as ``--param``; for
    fan-out the resumed agent is the *continuation*, not the sub, so it rides as
    ``--continue-param`` (a plain ``--param`` would land on the sub and be lost).
    """
    kind = payload.get("type")
    ttl = payload.get("ttl")

    if kind == "human":
        argv = [
            "homer-ask",
            "ask-and-dispatch",
            payload.get("text", ""),
            "--agent",
            agent_name,
            "--answer-param",
            RESUME_PARAM,
            "--param",
            f"{ENV_THREAD}={thread_id}",
            "--param",
            f"{ENV_INTERRUPT}={interrupt_id}",
        ]
        for option in payload.get("options") or []:
            argv += ["--option", option]
        if ttl is not None:
            argv += ["--ttl", str(ttl)]
        return argv

    if kind == "fanout":
        sub_agent = payload.get("agent")
        if not sub_agent:
            raise ValueError("fanout interrupt payload missing 'agent'")
        argv = [
            "homer-dispatch",
            "--agent",
            sub_agent,
            "--continue-agent",
            agent_name,
            "--continue-thread",
            thread_id,
            "--resume-param",
            RESUME_PARAM,
            # The interrupt id targets the resumed PARENT (the continuation), not the
            # sub — so it must ride the continuation channel, not --param. See README.md.
            "--continue-param",
            f"{ENV_INTERRUPT}={interrupt_id}",
        ]
        result_jsonpath = payload.get("result_jsonpath")
        if result_jsonpath:
            argv += ["--result-jsonpath", result_jsonpath]
        if ttl is not None:
            argv += ["--ttl", str(ttl)]
        for key, value in (payload.get("params") or {}).items():
            argv += ["--param", f"{key}={value}"]
        return argv

    raise ValueError(f"unknown interrupt payload type: {kind!r}")


def _join_groups(interrupts: list[Any]) -> dict[str, list[Any]]:
    """Group pending fanout interrupts by their non-None ``group`` key.

    Human gates and ungrouped fanouts (no ``group`` in the payload) are skipped,
    so they always take the single, one-resume-per-interrupt path.
    """
    groups: dict[str, list[Any]] = {}
    for i in interrupts:
        value = i.value
        if (
            isinstance(value, dict)
            and value.get("type") == "fanout"
            and value.get("group") is not None
        ):
            groups.setdefault(value["group"], []).append(i)
    return groups


def _join_body(members: list[Any], thread_id: str, agent_name: str) -> dict[str, Any]:
    """Build the ``/api/v1/callback/joins`` request body for a fan-out group.

    One sub per member interrupt (agent + params + result transform + the
    interrupt id its result is keyed under). The comma-joined sorted interrupt
    ids ride ``continueParams`` as ``HMR_LG_INTERRUPT`` — the signal the resume
    run uses to deliver the full result map. The continuation TTL, when any
    member sets one, is the largest member ttl.
    """
    ordered = sorted(members, key=lambda i: i.id)
    ids = [i.id for i in ordered]
    subs: list[dict[str, Any]] = []
    for i in ordered:
        value = i.value
        sub: dict[str, Any] = {"agent": value["agent"], "interruptId": i.id}
        if value.get("params"):
            sub["params"] = value["params"]
        if value.get("result_jsonpath"):
            sub["resultJsonPath"] = value["result_jsonpath"]
        subs.append(sub)
    body: dict[str, Any] = {
        "subs": subs,
        "continueAgent": agent_name,
        "continueThread": thread_id,
        "continueParams": {ENV_INTERRUPT: ",".join(ids)},
        "resumeParam": RESUME_PARAM,
    }
    ttls = [t for i in ordered if (t := i.value.get("ttl")) is not None]
    if ttls:
        body["ttlSec"] = max(ttls)
    return body


def _select_interrupt(interrupts: list[Any]) -> Any:
    """Pick the single interrupt to resolve this segment: the smallest stable id.

    A pure function of the interrupt set — deterministic regardless of insertion
    order or which thread parked them. (The ids themselves are assigned by
    LangGraph and are not stable across *fresh* runs, so determinism is "same id
    set → same choice", not "same branch across reruns".)
    """
    return min(interrupts, key=lambda i: i.id)


def _write_artifact(thread_id: str, module: str, segment: str) -> None:
    """Record this run's LangGraph identity for Homer, crash-safe.

    Written before the graph executes so a mid-run crash cannot lose the
    thread id; Homer's resume/status endpoints read it from the process's
    artifacts directory. Skipped when HMR_ARTIFACTS_DIR is unset (agentdebug).
    See ADR-0044.
    """
    artifacts_dir = os.environ.get(ENV_ARTIFACTS_DIR)
    if not artifacts_dir:
        return
    path = Path(artifacts_dir) / ARTIFACT_FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"threadId": thread_id, "module": module, "segment": segment})
    )


def run(module: str, input_env: str = ENV_INPUT) -> int:
    """Run or resume one workflow segment for the graph named by ``module``.

    On a fresh run the graph's initial JSON input is read from the env var
    named by ``input_env`` (the command's ``inputEnv``, defaulting to
    ``HMR_LG_INPUT``). Resume detection is independent of this.

    Returns a process exit code. Graph exceptions propagate (the process exits
    non-zero with the traceback on stderr).
    """
    thread_env = os.environ.get(ENV_THREAD) or ""
    resuming = bool(thread_env) and ENV_RESUME in os.environ
    # Recover mode (ADR-0044): re-enter a crashed thread from its latest
    # checkpoint. A stored HMR_LG_RESUME wins over recover on purpose — a
    # segment that crashed mid-interrupt-resume must replay its resume value.
    recovering = (
        bool(thread_env)
        and not resuming
        and os.environ.get(ENV_RECOVER) == "1"
    )

    state_dir = Path(os.environ[ENV_STATE_DIR])
    state_dir.mkdir(parents=True, exist_ok=True)
    agent_name = os.environ.get(ENV_AGENT_NAME, "")

    thread_id = thread_env or uuid.uuid4().hex
    config = {"configurable": {"thread_id": thread_id}}

    segment = "recover" if recovering else ("resume" if resuming else "fresh")
    _write_artifact(thread_id, module, segment)

    if resuming:
        # The parking segment chose a unit and recorded its id(s) in HMR_LG_INTERRUPT:
        #   - comma-joined ids → a join: HMR_LG_RESUME is the full {id: per_sub} map,
        #     resolving every interrupt in the group in this one invoke.
        #   - one id → a single targeted interrupt: a per-interrupt {id: value} map,
        #     leaving the other pending interrupts untouched.
        #   - absent → back-compat scalar resume.
        iid = os.environ.get(ENV_INTERRUPT) or ""
        if "," in iid:
            graph_input: Any = Command(resume=json.loads(os.environ[ENV_RESUME]))
        elif iid:
            graph_input = Command(resume={iid: os.environ[ENV_RESUME]})
        else:
            graph_input = Command(resume=os.environ[ENV_RESUME])
    elif recovering:
        graph_input = None
    else:
        graph_input = json.loads(os.environ.get(input_env) or "{}")

    conn = sqlite3.connect(str(state_dir / CHECKPOINT_FILENAME), check_same_thread=False)
    try:
        saver = SqliteSaver(conn)
        graph = _compile(_load_graph(module), saver)

        if recovering:
            snapshot = graph.get_state(config)
            if snapshot is None or snapshot.created_at is None:
                print(
                    f"homer_langgraph: no checkpoint for thread {thread_id}; cannot recover",
                    file=sys.stderr,
                )
                return 1
            if not snapshot.next:
                # Thread already completed — idempotent recover: emit final state.
                print(json.dumps(snapshot.values, default=str))
                return 0

        result = graph.invoke(graph_input, config)

        interrupts = result.get("__interrupt__") if isinstance(result, dict) else None
        if not interrupts:
            # Graph completed: emit the final state as JSON.
            print(json.dumps(result, default=str))
            return 0

        # Per-segment unit selection. A fan-out group (≥2 fanout interrupts sharing
        # a non-None `group`) is resolved as ONE join: dispatch all subs at once and
        # resume the thread a single time after the last finishes. Otherwise fall
        # back to today's path — resolve exactly one interrupt (smallest stable id),
        # leaving the rest pending for later segments. Either way a segment has
        # exactly one resume-producing side effect. See ADR-0026.
        eligible = {g: m for g, m in _join_groups(interrupts).items() if len(m) >= 2}
        if eligible:
            # The eligible group with the smallest min interrupt id wins; the rest
            # (other groups, ungrouped fanouts, human gates) stay pending.
            group_name = min(eligible, key=lambda g: min(i.id for i in eligible[g]))
            members = eligible[group_name]
            body = _join_body(members, thread_id, agent_name)
            subprocess.run(["homer-join"], input=json.dumps(body), text=True, check=True)
            # Status line goes to stderr: on the parked path stdout must stay clean
            # so a result-env/resultJsonPath mapping over this segment sees no prose.
            print(
                f"homer_langgraph: parked thread {thread_id} on join group "
                f"'{group_name}' ({len(members)} subs)",
                file=sys.stderr,
            )
            return 0

        chosen = _select_interrupt(interrupts)
        if len(interrupts) > 1:
            print(
                f"homer_langgraph: resolving interrupt {chosen.id} "
                f"(1 of {len(interrupts)})",
                file=sys.stderr,
            )
        payload = chosen.value

        argv = _resume_payload(payload, thread_id, agent_name, chosen.id)
        subprocess.run(argv, check=True)
        # Status line goes to stderr: on the parked path stdout must stay clean
        # so a result-env/resultJsonPath mapping over this segment sees no prose.
        print(
            f"homer_langgraph: parked thread {thread_id} on {payload.get('type')} gate",
            file=sys.stderr,
        )
        return 0
    finally:
        conn.close()
