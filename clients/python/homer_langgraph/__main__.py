"""CLI entry point: ``python -m homer_langgraph run --module <module>``."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Callable

from . import introspection, runner


def _add_module_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--module",
        required=True,
        help="Graph module spec: 'pkg.mod:attr' or 'pkg.mod' (default attr 'graph')",
    )


def _print_or_fail(command: str, produce: Callable[[], str]) -> int:
    """Print ``produce()``; on any failure report it on stderr and return 1."""
    try:
        print(produce())
        return 0
    except Exception as exc:  # noqa: BLE001 - report any import/compile/state failure
        print(f"homer_langgraph {command} failed: {exc}", file=sys.stderr)
        return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="homer_langgraph")
    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="Run or resume a LangGraph workflow segment")
    _add_module_arg(run_parser)
    run_parser.add_argument(
        "--input-env",
        default=runner.ENV_INPUT,
        help="Env var the fresh-run JSON graph input is read from (default HMR_LG_INPUT)",
    )

    inspect_parser = sub.add_parser(
        "inspect",
        help="Print the graph's Mermaid source, or with --json its topology (read-only)",
    )
    _add_module_arg(inspect_parser)
    inspect_parser.add_argument(
        "--json",
        action="store_true",
        help="Print {mermaid, topology} as one JSON object instead of raw Mermaid",
    )

    state_parser = sub.add_parser(
        "state", help="Print per-node status for a thread as JSON (read-only)"
    )
    _add_module_arg(state_parser)
    state_parser.add_argument("--thread", required=True, help="LangGraph thread id")

    args = parser.parse_args(argv)
    if args.command == "run":
        return runner.run(module=args.module, input_env=args.input_env)
    if args.command == "inspect":
        if args.json:
            return _print_or_fail(
                "inspect", lambda: json.dumps(introspection.inspect_graph(args.module))
            )
        return _print_or_fail("inspect", lambda: introspection.inspect_mermaid(args.module))
    # argparse's required subcommand rejects anything but run / inspect / state.
    return _print_or_fail(
        "state", lambda: json.dumps(introspection.thread_state(args.module, args.thread))
    )


if __name__ == "__main__":
    sys.exit(main())
