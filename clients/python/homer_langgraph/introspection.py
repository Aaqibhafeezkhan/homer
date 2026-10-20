"""Read-only introspection behind ``python -m homer_langgraph inspect`` / ``state``.

Imports and compiles a graph without running it: ``inspect`` reports its topology
(plus the deprecated Mermaid source), ``state`` a thread's per-node status from
the durable checkpoint store, opened read-only. Neither writes anything. Homer
calls both from ``LangGraphInspectService`` / ``LangGraphRunStatusService``; the
JSON they print is a contract described in ``README.md``. See ADR-0044, ADR-0073
and ADR-0075.
"""

from __future__ import annotations

import os
import sqlite3
import stat
from collections.abc import Iterable
from pathlib import Path
from typing import Any
from urllib.parse import quote

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END
from langgraph.graph.state import StateGraph
from langgraph.types import Send

from .runner import CHECKPOINT_FILENAME, ENV_STATE_DIR, _compile, _load_graph

# Per node, how many interrupt ids `interruptsSeen` reports. The history fold
# itself cannot be bounded -- a node's `done` may only be provable from the
# oldest snapshot -- but the payload can: a node that has served thousands of
# fan-outs would otherwise keep growing this JSON, the response Homer builds
# from it, and the per-id continuation lookup behind each entry, without
# telling an operator anything new. The newest ids are kept and any clipped
# node is named in `interruptsSeenTruncated`.
MAX_INTERRUPTS_SEEN_PER_NODE = 100

# `traversed` bounds (CWE-770). The checkpoint store is
# agent-writable, so a forged history could otherwise make every status poll
# scan an unbounded number of checkpoints and writes. Truncating only drops
# edges -- `traversed` is a display aid (ADR-0075), never a correctness input.
MAX_TRAVERSED_CHECKPOINTS = 10_000
MAX_TRAVERSED_WRITES = 100_000


def _compile_for_inspect(module: str) -> Any:
    graph = _load_graph(module)
    if isinstance(graph, StateGraph):
        return graph.compile()
    if callable(getattr(graph, "get_graph", None)):
        return graph
    raise TypeError(f"{graph!r} is not a StateGraph or compiled graph")


def graph_topology(drawn: Any) -> dict[str, Any]:
    """Nodes and edges of a drawn graph (``compiled.get_graph()``) as plain JSON.

    Deliberately not ``Graph.to_json()``: that carries runnable class paths the
    console has no use for. See ADR-0073.
    """
    return {
        "nodes": [{"id": node_id} for node_id in drawn.nodes],
        "edges": [
            {
                "source": edge.source,
                "target": edge.target,
                "label": None if edge.data is None else str(edge.data),
                "conditional": bool(edge.conditional),
            }
            for edge in drawn.edges
        ],
    }


def traversed_edges(
    topology: dict[str, Any],
    tasks: dict[str, tuple[str, bool]],
    checkpoints: Iterable[Any],
) -> list[dict[str, str]] | None:
    """The topology edges a thread took, read from its checkpoints' routing writes.

    ``tasks`` maps a task id to its node name and whether it completed;
    ``checkpoints`` are the thread's checkpoint tuples. A completed task that
    wrote no routing channel ended its branch. Only topology edges are kept:
    the checkpoint store is agent-writable. ``None`` (unknown) when completed
    tasks wrote no recognized routing channel at all. See ADR-0075.
    """
    # Longest first: node ids may themselves contain ":" or "+".
    node_ids = sorted((node["id"] for node in topology["nodes"]), key=len, reverse=True)
    edges = {(edge["source"], edge["target"]) for edge in topology["edges"]}
    # A recognized channel is a fixed prefix/suffix plus topology node ids, so
    # the bound covers every legitimate form: `branch:to:<target>` and a
    # `join:` of distinct node ids (a self-join may put the longest id on both
    # sides). A source repeated within one waiting edge (`add_edge([a, a, a], b)`
    # writes `join:a+a+a:b`) can exceed it and be dropped like any forged write
    # -- only an edge is lost, never correctness. CWE-770.
    max_id = max(map(len, node_ids), default=0)
    max_channel_len = max(
        len("branch:to:") + max_id,
        len("join:") + sum(len(node_id) + 1 for node_id in node_ids) + max_id,
    )
    routed: dict[str, set[Any]] = {}
    writes = 0
    checkpoints_seen = 0
    for checkpoint in checkpoints:
        for task_id, channel, value in checkpoint.pending_writes or ():
            if writes >= MAX_TRAVERSED_WRITES:
                break
            writes += 1
            if task_id not in tasks or not isinstance(channel, str):
                continue
            if len(channel) > max_channel_len:
                continue
            if channel.startswith("branch:to:"):
                target = channel.removeprefix("branch:to:")
            elif channel.startswith("join:"):
                # Only the writer's own edges: `join:a+p:q:join` also ends with `:q:join`.
                source = tasks[task_id][0]
                target = next(
                    (
                        n
                        for n in node_ids
                        if channel.endswith(":" + n) and (source, n) in edges
                    ),
                    None,
                )
            elif channel == "__pregel_tasks" and isinstance(value, Send):
                if isinstance(value.node, str) and len(value.node) > max_channel_len:
                    continue
                target = value.node if isinstance(value.node, str) else None
            else:
                continue
            routed.setdefault(task_id, set()).add(target)
        checkpoints_seen += 1
        if checkpoints_seen >= MAX_TRAVERSED_CHECKPOINTS or writes >= MAX_TRAVERSED_WRITES:
            break
    # `__start__` always writes `branch:to:`; none at all means the channel names
    # changed, and the END rule below would then invent edges.
    if not routed and any(completed for _, completed in tasks.values()):
        return None
    pairs: set[tuple[str, Any]] = set()
    for task_id, (name, completed) in tasks.items():
        if task_id in routed:
            pairs.update((name, target) for target in routed[task_id])
        elif completed:
            pairs.add((name, END))
    return [{"source": s, "target": t} for s, t in sorted(pairs & edges)]


def _draw_mermaid_or_none(drawn: Any) -> str | None:
    """The drawn graph's Mermaid source, or ``None`` when drawing fails."""
    try:
        return drawn.draw_mermaid()
    except Exception:  # noqa: BLE001
        return None


def _no_checkpoint(thread_id: str) -> ValueError:
    return ValueError(f"no checkpoint for thread {thread_id}")


def inspect_mermaid(module: str) -> str:
    """Return the Mermaid source for the graph named by ``module``.

    Read-only introspection: imports and compiles the graph WITHOUT a
    checkpointer (no SQLite, no side effects). Backs the legacy no-flag
    ``inspect`` mode; Homer calls :func:`inspect_graph`.
    """
    return _compile_for_inspect(module).get_graph().draw_mermaid()


def inspect_graph(module: str) -> dict[str, Any]:
    """``inspect --json``: the graph's topology plus its (deprecated) Mermaid source.

    Read-only, like :func:`inspect_mermaid`. A Mermaid draw failure degrades to
    ``mermaid: None``; the topology is what Homer renders. See ADR-0073.
    """
    drawn = _compile_for_inspect(module).get_graph()
    return {"mermaid": _draw_mermaid_or_none(drawn), "topology": graph_topology(drawn)}


def thread_state(module: str, thread_id: str) -> dict[str, Any]:
    """Per-node status for ``thread_id`` from the durable checkpoint store.

    Read-only: never invokes the graph and writes nothing at all.
    Status precedence per node: failed > parked > pending > done > unreached.
    Reports ``interrupts`` (per node, pending right now) and ``interruptsSeen``
    (per node, the newest ``MAX_INTERRUPTS_SEEN_PER_NODE`` ids in the thread's
    history, naming any clipped node in ``interruptsSeenTruncated``) so Homer
    can attribute both live and already-resolved sub-agent runs to their node.
    Used by Homer's ``GET /api/v1/processes/{id}/langgraph``. See ADR-0044.
    """
    state_dir = Path(os.environ[ENV_STATE_DIR])
    db_path = state_dir / CHECKPOINT_FILENAME
    # Read-only: connect() would otherwise create an empty checkpoints.sqlite
    # for a thread that was never started, contradicting the read-only claim.
    # CWE-59: the state dir is agent-writable, so
    # its store may be a planted link or special file. Open only a regular,
    # singly-linked file -- a symlink or hard link would make this server-host
    # read serve another database, a FIFO would block it. The lstat-to-open
    # window and a swapped parent dir are ADR-0047's accepted same-UID residual.
    try:
        st = os.lstat(db_path)
    except (FileNotFoundError, NotADirectoryError):
        st = None
    if st is None or not stat.S_ISREG(st.st_mode) or st.st_nlink != 1:
        raise _no_checkpoint(thread_id)
    # mode=ro: the state dir is agent-writable, and a read-write connection let
    # SqliteSaver's setup create tables and switch to WAL in a planted file.
    uri = f"file:{quote(str(db_path.absolute()))}?mode=ro"
    try:
        conn = sqlite3.connect(uri, uri=True, check_same_thread=False)
    except sqlite3.OperationalError as e:
        raise _no_checkpoint(thread_id) from e
    try:
        saver = SqliteSaver(conn)
        graph = _compile(_load_graph(module), saver)
        config = {"configurable": {"thread_id": thread_id}}
        latest = graph.get_state(config)
        if latest is None or latest.created_at is None:
            raise _no_checkpoint(thread_id)
        failed = {t.name: str(t.error) for t in latest.tasks if t.error is not None}
        parked = {t.name for t in latest.tasks if t.interrupts}
        interrupts = {
            t.name: sorted(i.id for i in t.interrupts)
            for t in latest.tasks
            if t.interrupts
        }
        pending = set(latest.next)
        # `done` and `seen` (the interrupt ids this thread has parked on,
        # per node -- Homer maps them to the sub-agent runs that served them, so a
        # node keeps its fan-out history after the join resolved) are both folds
        # over the whole checkpoint history.
        #
        # Deliberately recomputed per call. A file cache here was tried and
        # removed: writing it into the state directory, which agent code can also
        # write, kept yielding symlink/hard-link/TOCTOU findings, and it only ever
        # saved the fold -- ~3% of a status call, since a `state` subprocess is
        # almost entirely interpreter start plus the langgraph import. Repeated
        # polling is bounded on the Homer side instead, by a short-lived bounded
        # cache of this reading in LangGraphRunStatusService.
        done: set[str] = set()
        # An ordered set per node (dict keyed by id), because MAX_INTERRUPTS_SEEN_
        # PER_NODE keeps a prefix: the live park goes in first so a clipped node
        # still reports what it is parked on right now, then history, which
        # get_state_history yields newest-first. Re-assigning an existing key does
        # not move it, so a repeated id keeps its original (newer) position.
        seen: dict[str, dict[str, None]] = {}
        # Task id -> (node name, completed), for `traversed`.
        tasks: dict[str, tuple[str, bool]] = {}
        # get_state_history may not include the latest snapshot on every backend,
        # so the live park is read from `latest` regardless of what history holds.
        # A result means the node completed: a recover that succeeds keeps the failed
        # attempt's error on the same task, so the error alone does not mean failed.
        for task in latest.tasks:
            tasks[task.id] = (task.name, task.result is not None)
            for pending_interrupt in task.interrupts:
                seen.setdefault(task.name, {})[pending_interrupt.id] = None
        for snapshot in graph.get_state_history(config):
            for task in snapshot.tasks:
                completed = task.result is not None
                tasks[task.id] = (task.name, completed)
                if completed:
                    done.add(task.name)
                for pending_interrupt in task.interrupts:
                    seen.setdefault(task.name, {})[pending_interrupt.id] = None
        interrupts_seen = {
            node: sorted(list(ids)[:MAX_INTERRUPTS_SEEN_PER_NODE])
            for node, ids in seen.items()
        }
        interrupts_seen_truncated = sorted(
            node
            for node, ids in seen.items()
            if len(ids) > MAX_INTERRUPTS_SEEN_PER_NODE
        )
        # The graph is already compiled here, so the Mermaid source is nearly
        # free — including it saves Homer a second `inspect` subprocess (a
        # full interpreter start + langgraph import) per status request.
        drawn = graph.get_graph()
        nodes: dict[str, str] = {}
        for name in drawn.nodes:
            if name.startswith("__"):  # __start__ / __end__ are structural
                continue
            if name in failed:
                nodes[name] = "failed"
            elif name in parked:
                nodes[name] = "parked"
            elif name in pending:
                nodes[name] = "pending"
            elif name in done:
                nodes[name] = "done"
            else:
                nodes[name] = "unreached"
        # The mermaid source is a nice-to-have next to the per-node state:
        # a draw failure (dependency drift, exotic custom graph) must not
        # escape to the CLI catch-all and take nodes/errors/interrupts with it.
        mermaid = _draw_mermaid_or_none(drawn)
        try:
            topology: dict[str, Any] | None = graph_topology(drawn)
        except Exception:  # noqa: BLE001
            topology = None
        # Like `mermaid` and `topology`: a failure must not cost the rest.
        try:
            traversed: list[dict[str, str]] | None = (
                None
                if topology is None
                else traversed_edges(
                    topology,
                    tasks,
                    # Limit the read itself: a cap inside the scan still lets the
                    # store materialize every tuple, pending writes included,
                    # before the scan counts it (§12 rule 4).
                    saver.list(
                        {"configurable": {"thread_id": thread_id, "checkpoint_ns": ""}},
                        limit=MAX_TRAVERSED_CHECKPOINTS,
                    ),
                )
            )
        except Exception:  # noqa: BLE001
            traversed = None
        return {
            "threadId": thread_id,
            "nodes": nodes,
            "errors": failed,
            "interrupts": interrupts,
            "interruptsSeen": interrupts_seen,
            "interruptsSeenTruncated": interrupts_seen_truncated,
            "mermaid": mermaid,
            "topology": topology,
            "traversed": traversed,
        }
    except sqlite3.OperationalError as e:
        raise _no_checkpoint(thread_id) from e
    finally:
        conn.close()
