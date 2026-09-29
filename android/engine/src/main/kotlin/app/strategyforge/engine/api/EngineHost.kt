package app.strategyforge.engine.api

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the engine thread (D-029). Every engine call, from the UI's API client, the scheduler tick
 * or a finished AI request, runs on this one thread, so the engine never needs locks. Slow network
 * work (AI providers) runs on [background] and hands its result back with [post].
 */
class EngineHost(
    name: String = "sf-engine",
) : AutoCloseable {
    @Volatile private var engineThread: Thread? = null

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, name).also {
                it.isDaemon = true
                engineThread = it
            }
        }

    private val network: ExecutorService =
        Executors.newCachedThreadPool { r -> Thread(r, "$name-net").also { it.isDaemon = true } }

    fun onEngineThread(): Boolean = Thread.currentThread() === engineThread

    /** Runs [block] on the engine thread and waits for its result; re-entrant on that thread. */
    fun <T> call(block: () -> T): T {
        if (onEngineThread()) return block()
        try {
            return executor.submit<T> { block() }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Queues [block] on the engine thread without waiting. */
    fun post(block: () -> Unit) {
        executor.execute(block)
    }

    /** Runs [block] off the engine thread (network calls). */
    fun background(block: () -> Unit) {
        network.execute(block)
    }

    override fun close() {
        executor.shutdown()
        network.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
