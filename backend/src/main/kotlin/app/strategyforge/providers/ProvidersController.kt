package app.strategyforge.providers

import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.parseUuid
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ProviderTypeView(
    val type: String,
    val kind: String,
    val credentialRequired: Boolean,
    val credentialEnvironmentVariable: String?,
    val settings: Map<String, SettingSpec>,
)

@RestController
@RequestMapping("/v1/providers")
@Tag(name = "Configuration")
class ProvidersController(
    private val providers: ProviderService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ) = providers.list(includeArchived)

    @GetMapping("/types")
    fun types() = ProviderType.entries.map { ProviderTypeView(it.name, it.kind.name, it.credentialRequired, it.credentialEnv, it.settings) }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<ProviderView> = providers.get(parseUuid(id)).let { ETags.ok(it, it.version) }

    @PostMapping
    fun create(
        @RequestBody req: ProviderCreate,
    ): ResponseEntity<ProviderView> = providers.create(req).let { ETags.created(it, it.version) }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody req: ProviderUpdate,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ) = providers.update(parseUuid(id), req, ifMatch).let { ETags.ok(it, it.version) }

    @PutMapping("/{id}/credential")
    fun setCredential(
        @PathVariable id: String,
        @RequestBody req: CredentialUpdate,
    ) = providers.setCredential(parseUuid(id), req.credential)

    @DeleteMapping("/{id}/credential")
    fun removeCredential(
        @PathVariable id: String,
    ) = providers.setCredential(parseUuid(id), null)

    @PostMapping("/{id}/activate")
    fun activate(
        @PathVariable id: String,
    ) = providers.activate(parseUuid(id))

    @PostMapping("/{id}/archive")
    fun archive(
        @PathVariable id: String,
    ) = providers.archive(parseUuid(id))

    /** Capability diagnostics; results are stored and shown explicitly, including unsupported states. */
    @PostMapping("/{id}/test")
    fun test(
        @PathVariable id: String,
    ) = providers.test(parseUuid(id))
}
