package homer.app.services.persistence

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Invokes [method] on [target] and unwraps the reflection wrapper, so a proxy's caller sees the
 * driver's own exception. Shared by the `Connection`/`Statement` proxies in this package, which
 * differ only in what they intercept.
 */
internal fun delegate(
    target: Any,
    method: Method,
    args: Array<out Any?>?,
): Any? =
    try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
