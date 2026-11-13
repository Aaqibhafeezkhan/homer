package homer.app.fixtures

import java.util.concurrent.TimeUnit

/** The longest [waitUnfinished] waits per call: short, so a test's timeout stays its length. */
const val FAKE_WAIT_MS = 50L

/**
 * The wait inside a fake of a timed wait on something that has not finished (`Process.waitFor`, an SSH
 * channel's `waitFor`): it sleeps [timeout], capped at [FAKE_WAIT_MS], as the real call blocks, before
 * the fake answers "not yet". An instant answer busy-spins the caller's poll loop (`CLAUDE.md` §6).
 */
fun waitUnfinished(
    timeout: Long,
    unit: TimeUnit,
) = Thread.sleep(unit.toMillis(timeout).coerceIn(0, FAKE_WAIT_MS))
