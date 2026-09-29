package app.strategyforge.android.core.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Display formatting. Values arrive as decimal strings and are formatted with BigDecimal only
 * (NFR-002). Timestamps are UTC instants shown in the owner's timezone (NFR-001). Gains and losses
 * always carry a sign, an arrow and a word, never colour alone (NFR-009).
 */
class Formatters(
    private val zone: ZoneId,
    private val locale: Locale = Locale.US,
) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)

    fun decimal(value: String?): BigDecimal? = value?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /** Money in the display currency; CAD values are converted with the given timestamped rate. */
    fun money(
        value: String?,
        currency: String = "USD",
        usdToCad: BigDecimal? = null,
    ): String {
        val v = decimal(value) ?: return "—"
        val converted = if (currency == "CAD") usdToCad?.let { v.multiply(it) } ?: return "— CAD (no FX rate)" else v
        val f = DecimalFormat("#,##0.00", symbols)
        val prefix = if (currency == "CAD") "CA$" else "$"
        val abs = f.format(converted.abs().setScale(2, RoundingMode.HALF_EVEN))
        return if (converted.signum() < 0) "-$prefix$abs" else "$prefix$abs"
    }

    fun quantity(value: String?): String {
        val v = decimal(value) ?: return "—"
        val stripped = v.stripTrailingZeros()
        val scale = stripped.scale().coerceIn(0, MAX_QTY_SCALE)
        return DecimalFormat("#,##0" + if (scale > 0) "." + "0".repeat(scale) else "", symbols).format(v.setScale(scale, RoundingMode.DOWN))
    }

    fun percent(value: String?): String {
        val v = decimal(value) ?: return "—"
        return DecimalFormat("0.00", symbols).format(v.setScale(2, RoundingMode.HALF_EVEN)) + "%"
    }

    /** "▲ +$12.30 gain" / "▼ -$4.00 loss" / "■ $0.00 unchanged": accessible without colour. */
    fun pnl(
        value: String?,
        currency: String = "USD",
        usdToCad: BigDecimal? = null,
    ): PnlText {
        val v = decimal(value) ?: return PnlText("—", PnlDirection.UNKNOWN, "unknown")
        val money = money(v.abs().toPlainString(), currency, usdToCad)
        return when (v.signum()) {
            1 -> PnlText("▲ +$money", PnlDirection.GAIN, "gain of $money")
            -1 -> PnlText("▼ -$money", PnlDirection.LOSS, "loss of $money")
            else -> PnlText("■ $money", PnlDirection.FLAT, "unchanged")
        }
    }

    fun dateTime(iso: String?): String {
        val instant = parse(iso) ?: return "—"
        return DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(instant) + " " + zone.id
    }

    /** "3 min ago" for freshness labels on stale or cached data. */
    fun age(
        iso: String?,
        now: Instant,
    ): String {
        val instant = parse(iso) ?: return "unknown age"
        return age(instant, now)
    }

    fun age(
        instant: Instant,
        now: Instant,
    ): String {
        val d = Duration.between(instant, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.seconds < 60 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 48 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} days ago"
        }
    }

    /** Chart axis time (D-038): clock time for spans under 36 hours, day for shorter than 4 months, else month. */
    fun chartTime(
        epochMillis: Long,
        spanMillis: Long,
    ): String {
        val pattern =
            when {
                spanMillis < Duration.ofHours(36).toMillis() -> "HH:mm"
                spanMillis < Duration.ofDays(120).toMillis() -> "MMM d"
                else -> "MMM yyyy"
            }
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(epochMillis))
    }

    /** Short chart price label: "64,512", "1.2346", "0.004512", "$1.25M" style magnitudes without the symbol. */
    fun chartNumber(v: Double): String {
        val a = kotlin.math.abs(v)
        return when {
            a >= 1_000_000_000 -> DecimalFormat("0.##", symbols).format(v / 1_000_000_000) + "B"
            a >= 1_000_000 -> DecimalFormat("0.##", symbols).format(v / 1_000_000) + "M"
            a >= 10_000 -> DecimalFormat("#,##0", symbols).format(v)
            a >= 100 -> DecimalFormat("#,##0.00", symbols).format(v)
            a >= 1 -> DecimalFormat("0.0000", symbols).format(v)
            a == 0.0 -> "0"
            else -> DecimalFormat("0.000000", symbols).format(v)
        }
    }

    /** Epoch millis of an ISO instant, for plotting; null when it cannot be parsed. */
    fun epochMillis(iso: String?): Long? = parse(iso)?.toEpochMilli()

    private fun parse(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    companion object {
        const val MAX_QTY_SCALE = 8
    }
}

enum class PnlDirection { GAIN, LOSS, FLAT, UNKNOWN }

data class PnlText(
    val text: String,
    val direction: PnlDirection,
    /** Spoken description for screen readers. */
    val contentDescription: String,
)
