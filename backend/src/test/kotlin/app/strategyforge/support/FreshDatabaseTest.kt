package app.strategyforge.support

import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Integration test running against its own empty database (separate Spring context). */
@DirtiesContext
abstract class FreshDatabaseTest : BaseIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun freshDatasource(registry: DynamicPropertyRegistry) {
            val url = Postgres.freshDatabase("sf_fresh_" + System.nanoTime())
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { Postgres.container.username }
            registry.add("spring.datasource.password") { Postgres.container.password }
        }
    }
}
