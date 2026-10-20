package homer.agentdebug.stubs

import homer.app.models.Command

/**
 * Thin stub satisfying the executor-factory contract for `!<runnerCmd>` in the
 * server-less debugger. runnerCmd targets another configured runner alias via
 * the DI-wired RunnerRegistry, which agentdebug does not build — so, like
 * sub-agent and LangGraph runs, these commands are skipped (DebugAgentRunner
 * skips them before execution; this stub is the no-op fallback).
 */
class SkipRunnerCmdExecutor : SkipExecutor<Command.RunnerCmd>()
