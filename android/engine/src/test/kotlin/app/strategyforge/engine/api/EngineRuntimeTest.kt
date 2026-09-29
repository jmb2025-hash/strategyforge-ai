package app.strategyforge.engine.api

import app.strategyforge.engine.Engine
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.notifications.NotificationView
import app.strategyforge.engine.support.TestEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList

/** The phone's runtime wiring: ticks, notification delivery and reload after a restore. */
class EngineRuntimeTest {
    @TempDir lateinit var backups: File

    private val shown = CopyOnWriteArrayList<NotificationView>()
    private val backend by lazy { JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")) }
    private var created = 0
    private val runtime =
        EngineRuntime(
            create = {
                created++
                Engine(backend, fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() }, backupDir = { backups })
            },
            show = { shown += it },
        )

    @AfterEach
    fun stop() = runtime.close()

    private fun call(
        method: String,
        path: String,
        body: JsonObject? = null,
    ): LocalResponse = runtime.handle(LocalRequest(method, path, body = body))

    private fun LocalResponse.json() =
        app.strategyforge.engine.common.EngineJson
            .parseToJsonElement(String(body))

    @Test
    fun `demo ticks advance replay time and every tick is safe to repeat`() {
        val before = runtime.host.call { runtime.engine.marketClock.now() }
        val report = runtime.tick()!!
        assertThat(report.ran).containsExactly("replay+1m")
        assertThat(runtime.host.call { runtime.engine.marketClock.now() }).isAfter(before)
    }

    @Test
    fun `engine notifications are shown once each, after the change commits`() {
        call("POST", "/v1/emergency/pause-all", JsonObject(mapOf("enabled" to kotlinx.serialization.json.JsonPrimitive(true))))
        runtime.host.call { } // drain posted deliveries
        assertThat(shown).isNotEmpty()
        val ids = shown.map { it.id }.toSet()
        assertThat(runtime.host.call { runtime.engine.notifications.pendingLocal() }).isEmpty()
        runtime.tick()
        runtime.host.call { }
        assertThat(shown.map { it.id }.toSet()).isEqualTo(ids)
    }

    @Test
    fun `a restored backup reloads the engine so every screen sees the restored data`() {
        call("POST", "/v1/portfolios", JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("Kept"))))
        val name =
            call("POST", "/v1/backups")
                .json()
                .jsonObject["file"]!!
                .jsonObject["name"]!!
                .jsonPrimitive.content
        call("POST", "/v1/portfolios", JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("Dropped"))))
        runtime.host.call { runtime.engine.auth.confirmed() }
        assertThat(created).isEqualTo(1)
        assertThat(call("POST", "/v1/backups/$name/restore").status).isEqualTo(200)
        assertThat(created).isEqualTo(2)
        val names = call("GET", "/v1/portfolios").json().jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertThat(names).containsExactly("Kept")
    }
}
