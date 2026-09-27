package app.strategyforge.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/** Single PostgreSQL container shared by all integration tests (started once per JVM). */
object Postgres {
    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("sf_shared")
            .withUsername("sf")
            .withPassword("sf-test-password")
            .also { it.start() }
    }

    /** Creates a fresh, empty database in the shared container and returns its JDBC URL. */
    fun freshDatabase(name: String): String {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
            c.createStatement().use { s ->
                s.execute("drop database if exists $name with (force)")
                s.execute("create database $name")
            }
        }
        return container.jdbcUrl.replace("/sf_shared", "/$name")
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcClient

    protected val baseUrl: String get() = "http://localhost:$port"

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { Postgres.container.jdbcUrl }
            registry.add("spring.datasource.username") { Postgres.container.username }
            registry.add("spring.datasource.password") { Postgres.container.password }
        }
    }
}
