package homer.app.runners.queue

import homer.app.runners.RunnerSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps a backend session so the consumer's permit is released when the run
 * closes it. Delegation covers every RunnerSession member; only close() is
 * intercepted. Idempotent like the sessions it wraps.
 */
internal class PermitReleasingSession(
    private val delegate: RunnerSession,
    private val onClose: () -> Unit,
) : RunnerSession by delegate {
    private val released = AtomicBoolean(false)

    override fun close() {
        try {
            delegate.close()
        } finally {
            if (released.compareAndSet(false, true)) onClose()
        }
    }
}
