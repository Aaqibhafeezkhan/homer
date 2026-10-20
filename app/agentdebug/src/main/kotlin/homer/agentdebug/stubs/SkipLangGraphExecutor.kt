package homer.agentdebug.stubs

import homer.app.models.Command

/**
 * Thin stub satisfying the executor-factory contract for `!<langgraph>` in the
 * server-less debugger. A durable LangGraph workflow resumes across segments
 * via Homer's dispatch/continuation machinery, which agentdebug does not run —
 * so, like sub-agent runs, these commands are skipped (DebugAgentRunner skips
 * them before execution; this stub is the no-op fallback).
 */
class SkipLangGraphExecutor : SkipExecutor<Command.LangGraph>()
