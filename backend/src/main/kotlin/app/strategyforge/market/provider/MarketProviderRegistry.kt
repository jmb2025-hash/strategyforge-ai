package app.strategyforge.market.provider

import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.market.Capability
import app.strategyforge.market.CapabilityState
import app.strategyforge.market.MarketClock
import app.strategyforge.providers.MarketProviderActivated
import app.strategyforge.providers.ProviderCreate
import app.strategyforge.providers.ProviderService
import app.strategyforge.providers.ProviderTestResult
import app.strategyforge.providers.ProviderTester
import app.strategyforge.providers.ProviderType
import app.strategyforge.providers.ResolvedProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class ActiveProvider(
    val id: UUID,
    val type: ProviderType,
    val provider: MarketDataProvider,
)

/**
 * Holds the single active market-data adapter. At first start it creates and activates the
 * provider named by MARKET_PROVIDER (default REPLAY). The market clock follows the provider:
 * REPLAY uses the deterministic replay clock, live providers use the wall clock.
 */
@Component
class MarketProviderRegistry(
    private val providers: ProviderService,
    private val replay: ReplayProvider,
    private val fixtures: ReplayFixtures,
    private val clock: MarketClock,
    private val wall: Clock,
    private val props: StrategyForgeProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val current = AtomicReference<ActiveProvider?>(null)

    @EventListener(ApplicationReadyEvent::class)
    @Order(0)
    fun initialize() {
        ensureActive()
    }

    @Synchronized
    fun ensureActive(): ActiveProvider {
        current.get()?.let { return it }
        var id = providers.activeMarketProviderId()
        if (id == null) {
            val type = runCatching { ProviderType.valueOf(props.marketProvider.trim().uppercase()) }.getOrDefault(ProviderType.REPLAY)
            val created = providers.create(ProviderCreate(type.name, if (type == ProviderType.REPLAY) "Replay (synthetic)" else type.name, emptyMap()))
            id =
                if (type.credentialRequired && !created.credential.configured) {
                    log.warn("MARKET_PROVIDER={} has no credential; falling back to REPLAY", type)
                    providers.activate(providers.create(ProviderCreate("REPLAY", "Replay (synthetic)")).id).id
                } else {
                    providers.activate(created.id).id
                }
            providers.test(id)
        }
        return load(id)
    }

    @EventListener
    fun onActivated(e: MarketProviderActivated) {
        current.set(null)
        load(e.providerId)
    }

    @EventListener
    fun onTested(e: app.strategyforge.providers.ProviderTested) {
        if (current.get()?.id == e.providerId) reload()
    }

    private fun load(id: UUID): ActiveProvider {
        val resolved = providers.resolve(id)
        val adapter = build(resolved)
        if (resolved.type == ProviderType.REPLAY) clock.useReplay(fixtures.dataset.replayStart) else clock.useLive()
        val active = ActiveProvider(id, resolved.type, adapter)
        current.set(active)
        return active
    }

    fun build(resolved: ResolvedProvider): MarketDataProvider =
        when (resolved.type) {
            ProviderType.REPLAY -> replay
            ProviderType.TWELVE_DATA -> TwelveDataProvider(resolved, wall, detectedCapabilities(resolved.id))
            else -> throw IllegalArgumentException("${resolved.type} is not a market-data provider")
        }

    private fun detectedCapabilities(id: UUID): Map<Capability, CapabilityState> =
        providers
            .capabilities(id)
            .mapNotNull { c ->
                val cap = Capability.entries.firstOrNull { it.name == c.capability } ?: return@mapNotNull null
                cap to CapabilityState.valueOf(c.status)
            }.toMap()

    fun active(): ActiveProvider = current.get() ?: ensureActive()

    /** Called after diagnostics so the live adapter uses freshly detected capabilities. */
    fun reload() {
        val id = current.get()?.id ?: return
        current.set(null)
        load(id)
    }
}

@Component
class MarketProviderTester(
    private val registry: org.springframework.beans.factory.ObjectProvider<MarketProviderRegistry>,
    private val clock: MarketClock,
) : ProviderTester {
    override fun supports(type: ProviderType) = type == ProviderType.REPLAY || type == ProviderType.TWELVE_DATA

    override fun test(provider: ResolvedProvider): ProviderTestResult {
        val reg = registry.getObject()
        // Probe with no prior capability assumptions.
        val adapter = if (provider.type == ProviderType.TWELVE_DATA) TwelveDataProvider(provider, Clock.systemUTC(), emptyMap()) else reg.build(provider)
        val now = if (provider.type == ProviderType.REPLAY) clock.now() else Clock.systemUTC().instant()
        return adapter.diagnose(now)
    }
}
