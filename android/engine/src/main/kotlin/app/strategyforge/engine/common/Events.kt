package app.strategyforge.engine.common

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Synchronous in-process event bus replacing the server's Spring application events. Handlers run
 * on the publishing thread, inside the publisher's transaction, in subscription order.
 */
class EngineEvents {
    private val handlers = CopyOnWriteArrayList<Pair<Class<*>, (Any) -> Unit>>()

    fun <T : Any> on(
        type: Class<T>,
        handler: (T) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        handlers += type to { e: Any -> handler(e as T) }
    }

    inline fun <reified T : Any> on(noinline handler: (T) -> Unit) = on(T::class.java, handler)

    fun publish(event: Any) {
        handlers.filter { it.first.isInstance(event) }.forEach { it.second(event) }
    }
}
