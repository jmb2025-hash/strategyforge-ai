package app.strategyforge.engine.api

import app.strategyforge.engine.Engine
import app.strategyforge.engine.EngineScheduler
import app.strategyforge.engine.TickReport
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.notifications.NotificationView

/**
 * Everything the phone app runs (D-027, D-031): the engine on its own thread, the in-process API,
 * the scheduler tick and local notification delivery. The Android layer only supplies the
 * storage, key store and notification display, so this wiring is tested on the JVM.
 *
 * [create] builds an engine; it runs on the engine thread and is called again after a backup
 * restore, so every service reloads from the restored database.
 */
class EngineRuntime(
    private val create: (EngineHost) -> Engine,
    private val show: (NotificationView) -> Unit = {},
    val host: EngineHost = EngineHost(),
) : AutoCloseable {
    private val log = EngineLog.of(javaClass)

    @Volatile private var current: Loaded? = null

    private class Loaded(
        val engine: Engine,
        val scheduler: EngineScheduler,
        val api: LocalApi,
    )

    private fun loaded(): Loaded = current ?: host.call { current ?: load().also { current = it } }

    private fun load(): Loaded {
        val engine = create(host)
        engine.notifications.listener = { n -> host.post { deliver(n) } }
        val scheduler = EngineScheduler(engine)
        host.post { flushPending(engine) }
        return Loaded(engine, scheduler, LocalApi(engine, scheduler))
    }

    /** The engine, for platform code that must run on the engine thread (use [host]). */
    val engine: Engine get() = loaded().engine

    /** Answers one app request on the engine thread; a successful restore reloads the engine. */
    fun handle(request: LocalRequest): LocalResponse =
        host.call {
            val response = loaded().api.handle(request)
            if (request.method == "POST" && RESTORE.matches(request.path) && response.status == 200) {
                log.info("Backup restored; reloading the engine")
                current = load()
            }
            response
        }

    fun interceptor(): LocalApiInterceptor = LocalApiInterceptor(host, ::handle)

    /** One scheduler tick (called every [EngineScheduler.TICK_INTERVAL] by the foreground service). */
    fun tick(): TickReport? =
        try {
            host.call { loaded().scheduler.tick() }
        } catch (e: RuntimeException) {
            log.error("Engine tick failed", e)
            null
        }

    private fun deliver(n: NotificationView) {
        val engine = current?.engine ?: return
        runCatching { show(n) }.onFailure { log.error("Could not show notification {}", n.id, it) }
        engine.notifications.markShown(n.id)
    }

    /** Shows notifications raised while nothing could display them (for example before a reboot). */
    private fun flushPending(engine: Engine) {
        val pending = engine.notifications.pendingLocal()
        pending.takeLast(MAX_FLUSH).forEach { n -> runCatching { show(n) } }
        pending.forEach { engine.notifications.markShown(it.id) }
    }

    override fun close() = host.close()

    companion object {
        private val RESTORE = Regex("^/v1/backups/[^/]+/restore$")
        private const val MAX_FLUSH = 5
    }
}
