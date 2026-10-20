"""Fetch a large continuation result by reference via the homer-result wrapper.

When a fan-out sub-process's result exceeds Homer's inline cap, the resume
payload carries ``result: null`` plus a ``resultRef`` capability token instead
of the value. This helper redeems that token through the ``homer-result``
wrapper and returns the (possibly transformed) full result. See ADR-0025.
"""

from __future__ import annotations

import subprocess
from typing import Any


def homer_fetch_result(ref: dict[str, Any]) -> str:
    """Fetch the full result behind a ``resultRef`` capability token.

    Shells out to ``homer-result --token <token>`` and returns the result body
    as a string. The GET is idempotent and the token is reusable within its
    TTL, so this is safe to call again on a re-run of the node.

    Args:
        ref: The resume payload's ``resultRef`` dict (``{processId, path, token,
            sizeBytes}``).

    Returns:
        The (transformed) result body fetched by reference.
    """
    try:
        completed = subprocess.run(
            ["homer-result", "--token", ref["token"]],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError as exc:
        # The token rides on argv, so CalledProcessError.__str__ embeds it; an
        # uncaught traceback is captured into Homer's process logs, leaking a
        # live capability token. Re-raise without the token, and use `from None`
        # to drop the chained CalledProcessError whose str() would re-expose it.
        raise RuntimeError(
            f"homer-result failed (exit {exc.returncode}) fetching resultRef "
            f"for process {ref.get('processId')} path {ref.get('path')!r}"
        ) from None
    return completed.stdout
