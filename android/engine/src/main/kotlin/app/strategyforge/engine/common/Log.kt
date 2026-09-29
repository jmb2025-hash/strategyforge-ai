package app.strategyforge.engine.common

/**
 * Minimal logger with SLF4J-style `{}` placeholders so ported code keeps its messages. The app
 * points [EngineLog.sink] at Logcat; messages pass through [SecretRedactor] first (NFR-004).
 */
class EngineLogger(
    private val tag: String,
) {
    fun debug(
        msg: String,
        vararg args: Any?,
    ) = EngineLog.emit(EngineLog.Level.DEBUG, tag, msg, args)

    fun info(
        msg: String,
        vararg args: Any?,
    ) = EngineLog.emit(EngineLog.Level.INFO, tag, msg, args)

    fun warn(
        msg: String,
        vararg args: Any?,
    ) = EngineLog.emit(EngineLog.Level.WARN, tag, msg, args)

    fun error(
        msg: String,
        vararg args: Any?,
    ) = EngineLog.emit(EngineLog.Level.ERROR, tag, msg, args)
}

object EngineLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: (Level, String, String, Throwable?) -> Unit = { _, _, _, _ -> }

    fun of(type: Class<*>) = EngineLogger(type.simpleName)

    internal fun emit(
        level: Level,
        tag: String,
        msg: String,
        args: Array<out Any?>,
    ) {
        val t = args.lastOrNull() as? Throwable
        val values = if (t != null) args.dropLast(1) else args.toList()
        val sb = StringBuilder()
        var i = 0
        var n = 0
        while (i < msg.length) {
            if (i + 1 < msg.length && msg[i] == '{' && msg[i + 1] == '}' && n < values.size) {
                sb.append(values[n++])
                i += 2
            } else {
                sb.append(msg[i++])
            }
        }
        runCatching { sink(level, tag, SecretRedactor.redact(sb.toString()) ?: "", t) }
    }
}
