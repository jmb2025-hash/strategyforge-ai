package app.strategyforge.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

class ReplayFixtureManifestTest {
    private val root = File(System.getProperty("sf.repoRoot"), "fixtures/replay")

    @Test
    fun `NFR-012 replay fixtures match their manifest checksums and are labelled synthetic`() {
        val manifest = jacksonObjectMapper().readTree(File(root, "manifest.json"))
        assertThat(manifest["synthetic"].asBoolean()).isTrue()
        val files = manifest["files"]
        assertThat(files.size()).isGreaterThan(30)
        files.fields().forEach { (rel, sha) ->
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(File(root, rel).readBytes()))
            assertThat(digest).`as`(rel).isEqualTo(sha.asText())
        }
    }
}
