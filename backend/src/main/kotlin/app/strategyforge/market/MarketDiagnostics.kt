package app.strategyforge.market

import app.strategyforge.market.data.MarketDataIngestion
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.market.provider.MarketProviderRegistry
import app.strategyforge.operations.ComponentHealth
import app.strategyforge.operations.DiagnosticsContributor
import app.strategyforge.operations.HealthStatus
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class MarketDiagnostics(
    private val registry: MarketProviderRegistry,
    private val data: MarketDataService,
    private val ingestion: MarketDataIngestion,
    private val clock: MarketClock,
    private val instruments: InstrumentService,
) : DiagnosticsContributor {
    override fun diagnose(now: Instant): List<ComponentHealth> {
        val a = registry.active()
        val caps = a.provider.capabilities()
        val unsupported = caps.filterValues { it != CapabilityState.SUPPORTED }.keys.map { it.name }
        val provider =
            ComponentHealth(
                "market-provider",
                if (caps[Capability.EQUITY_QUOTES] == CapabilityState.SUPPORTED || caps[Capability.CRYPTO_QUOTES] == CapabilityState.SUPPORTED) HealthStatus.OK else HealthStatus.DEGRADED,
                "${a.type} active (${clock.mode()} clock)" + if (a.type.name == "REPLAY") "; data is SYNTHETIC" else "",
                mapOf("providerId" to a.id, "type" to a.type.name, "capabilities" to caps.mapKeys { it.key.name }, "notSupported" to unsupported, "marketTime" to clock.now()),
                now,
            )
        val mt = clock.now()
        val ages =
            instruments.byIds(ingestion.interestIds()).mapNotNull { i -> data.latestQuote(i.id)?.let { i.symbol to Duration.between(it.exchangeTs, mt).seconds } }
        val flags = data.flagCounts().filterKeys { it != "OK" }
        val last = ingestion.lastReport.get()
        val freshness =
            ComponentHealth(
                "data-freshness",
                when {
                    flags.isNotEmpty() -> HealthStatus.DEGRADED
                    last != null && last.failures.isNotEmpty() -> HealthStatus.DEGRADED
                    else -> HealthStatus.OK
                },
                if (ages.isEmpty()) "No instruments of interest yet" else "Max quote age ${ages.maxOf { it.second }}s across ${ages.size} instrument(s)",
                mapOf("quoteAgeSeconds" to ages.toMap(), "flags" to flags, "lastIngestionAt" to last?.at, "failures" to last?.failures),
                now,
            )
        return listOf(provider, freshness)
    }
}
