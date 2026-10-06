@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.strategyforge.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.format.PnlDirection
import app.strategyforge.android.core.state.ActionState
import java.time.Instant
import java.time.ZoneId

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun SfCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier.fillMaxWidth().padding(vertical = 5.dp).animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, Sf.colors.cardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
    }
}

/** The large number at the top of a screen (portfolio value), with its change and an optional chart below. */
@Composable
fun HeroCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    change: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Card(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Sf.colors.cardBorder),
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(Sf.colors.heroStart, Sf.colors.heroEnd)))
                .chartBackdrop(Sf.colors.grid.copy(alpha = 0.45f))
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The number rolls up or down when it changes, like a ticker.
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val up =
                        (initialState.filter { it.isDigit() || it == '.' }.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) <
                            (targetState.filter { it.isDigit() || it == '.' }.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                },
                label = "hero-value",
            ) { v -> Text(v, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 2.dp).testTag("hero-value")) }
            change?.let { Box(Modifier.padding(top = 6.dp)) { it() } }
            content()
        }
    }
}

/** "▲ +$12.30 (+1.20%)" in a tinted pill; readable without colour. */
@Composable
fun ChangePill(
    amount: String?,
    percent: String?,
    fmt: Formatters,
    suffix: String = "",
) {
    val p = fmt.pnl(amount)
    val color =
        when (p.direction) {
            PnlDirection.GAIN -> Sf.colors.gain
            PnlDirection.LOSS -> Sf.colors.loss
            else -> Sf.colors.neutral
        }
    val pct = percent?.let { fmt.decimal(it) }?.let { " (" + (if (it.signum() > 0) "+" else "") + fmt.percent(it.toPlainString()) + ")" } ?: ""
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            p.text
                .substringBefore(" gain")
                .substringBefore(" loss")
                .substringBefore(" unchanged") + pct + suffix,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).semantics { contentDescription = p.contentDescription + pct + suffix },
        )
    }
}

/** A row of single-choice pills (ranges, timeframes, metrics). */
@Composable
fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, label = "chip-bg")
            val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "chip-fg")
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(50),
                color = bg,
                contentColor = fg,
                modifier = Modifier.testTag("$tagPrefix-$value").semantics { this.selected = on },
            ) { Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) }
        }
    }
}

@Composable
fun LabelValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Gain/loss with arrow, sign and a spoken description; colour is only a secondary cue. */
@Composable
fun PnlValue(
    label: String,
    value: String?,
    fmt: Formatters,
) {
    val p = fmt.pnl(value)
    val color =
        when (p.direction) {
            PnlDirection.GAIN -> Sf.colors.gain
            PnlDirection.LOSS -> Sf.colors.loss
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) { contentDescription = "$label: ${p.contentDescription}" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.text, color = color, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("pnl"))
    }
}

/** Status banners for the required UI states (section 16). */
enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun Banner(
    text: String,
    kind: BannerKind = BannerKind.INFO,
    modifier: Modifier = Modifier,
) {
    val (accent, prefix) =
        when (kind) {
            BannerKind.INFO -> MaterialTheme.colorScheme.secondary to "Info"
            BannerKind.WARNING -> MaterialTheme.colorScheme.tertiary to "Warning"
            BannerKind.ERROR -> MaterialTheme.colorScheme.error to "Error"
        }
    Surface(color = accent.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Text(
                "$prefix: $text",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("banner"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Freshness labelling for cached/offline data: stale data is never shown as current. */
@Composable
fun FreshnessBanner(
    resource: Resource.Data<*>,
    fmt: Formatters,
    now: Instant = Instant.now(),
) {
    when {
        resource.offline -> Banner("Offline. Showing data from ${fmt.age(resource.fetchedAt, now)}; actions are unavailable until the engine responds.", BannerKind.WARNING)
        resource.error is ApiError.Unauthorized -> Banner("Please unlock the app again.", BannerKind.ERROR)
        resource.error != null -> Banner("Could not refresh: ${resource.error?.message}. Showing data from ${fmt.age(resource.fetchedAt, now)}.", BannerKind.WARNING)
        resource.refreshing && resource.fromCache -> Banner("Showing cached data from ${fmt.age(resource.fetchedAt, now)}; refreshing…")
        resource.stale -> Banner("Data from ${fmt.age(resource.fetchedAt, now)} may be stale.", BannerKind.WARNING)
    }
}

@Composable
fun <T> ResourceContent(
    resource: Resource<T>,
    fmt: Formatters,
    onRetry: () -> Unit,
    empty: (T) -> Boolean = { false },
    emptyText: String = "Nothing here yet.",
    art: Art = Art.CHART,
    content: @Composable (T) -> Unit,
) {
    when (resource) {
        Resource.Loading -> Loading()
        is Resource.Failure -> ErrorState(resource.error, onRetry)
        is Resource.Data ->
            Column {
                FreshnessBanner(resource, fmt)
                if (empty(resource.value)) EmptyState(emptyText, art) else content(resource.value)
            }
    }
}

@Composable
fun Loading() {
    Skeleton()
}

@Composable
fun ErrorState(
    error: ApiError,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        val text =
            when (error) {
                is ApiError.Offline -> "The on-device engine did not respond and nothing is cached yet. Try again in a moment."
                is ApiError.Http -> if (error.permissionDenied) "Permission denied: ${error.message}" else error.message ?: "Request failed"
                else -> error.message ?: "Request failed"
            }
        Banner(text, BannerKind.ERROR)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

/** Shows the result of the last action; failures that need recent authentication open the device-lock prompt. */
@Composable
fun ActionFeedback(
    action: ActionState,
    onReauth: (() -> Unit)? = null,
) {
    // A failure is brought on screen, so it is seen even when the button that caused it is far up (D-065).
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(action) { if (action is ActionState.Failed) runCatching { bring.bringIntoView() } }
    when (action) {
        ActionState.Idle -> Unit
        ActionState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Working" })
        is ActionState.Done -> Banner(action.message)
        is ActionState.Failed ->
            Column(Modifier.bringIntoViewRequester(bring)) {
                Banner(action.message, BannerKind.ERROR)
                if (action.needsReauth && onReauth != null) Button(onClick = onReauth) { Text("Confirm it's you") }
            }
    }
}

@Composable
fun StatusChip(status: String) {
    val (text, kind) =
        when (status) {
            "MANUAL_REVIEW_REQUIRED" -> "Manual Review Required" to BannerKind.WARNING
            "VALIDATION_FAILED", "FAILED", "REJECTED", "SUSPENDED", "BLOCKED" -> status.lowercase().replace('_', ' ') to BannerKind.ERROR
            "ACTIVE_AUTONOMOUS" -> "Autonomous active" to BannerKind.WARNING
            "PAUSED" -> "Paused" to BannerKind.WARNING
            else -> status.lowercase().replace('_', ' ') to BannerKind.INFO
        }
    val accent =
        when (kind) {
            BannerKind.INFO -> if (status.startsWith("ACTIVE") || status in setOf("OK", "FILLED", "COMPLETED", "VALIDATED", "PAPER_ELIGIBLE", "SUCCEEDED", "COMPILED")) Sf.colors.gain else Sf.colors.neutral
            BannerKind.WARNING -> MaterialTheme.colorScheme.tertiary
            BannerKind.ERROR -> MaterialTheme.colorScheme.error
        }
    Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    /** Shown under the field in the error colour (D-065). */
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    if (password) {
                        KeyboardType.Password
                    } else if (number) {
                        KeyboardType.Decimal
                    } else {
                        KeyboardType.Text
                    },
            ),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Moves the screen to a form field with a problem and puts the cursor there (D-065). Attach
 * [modifier] to the field; [show] scrolls to it and focuses it.
 */
class FieldTarget {
    val bring = BringIntoViewRequester()
    val focus = FocusRequester()
    val modifier: Modifier get() = Modifier.bringIntoViewRequester(bring).focusRequester(focus)

    suspend fun show() {
        runCatching { bring.bringIntoView() }
        runCatching { focus.requestFocus() }
    }
}

/** One [FieldTarget] per field name, kept across recompositions. */
@Composable
fun rememberFieldTargets(vararg fields: String): Map<String, FieldTarget> = remember { fields.associateWith { FieldTarget() } }

/**
 * "Confirm it's you" with the phone's own lock (fingerprint, face or screen-lock PIN), D-029.
 * [onConfirm] runs only after the prompt succeeds; its password arguments are unused on the phone.
 */
@Composable
fun ReauthDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String, totp: String?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        app.strategyforge.android.platform.DeviceAuth.prompt(
            context,
            "Confirm it's you",
            "This action changes safety settings or keys",
            onSuccess = { onConfirm("", null) },
            onCancel = onDismiss,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatters(timezone: String?): Formatters = Formatters(runCatching { ZoneId.of(timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC")))

/** How often screens showing live values re-read them while open (D-056). */
const val LIVE_REFRESH_MS = 5_000L

/** TSX intraday prices refresh about once a minute, so their screens re-read less often. */
const val TSX_REFRESH_MS = 30_000L

/**
 * Calls [onRefresh] every [everyMs] while the screen is in the foreground (D-056), so values that
 * move with live prices update without a manual refresh; it stops when the app is in the background.
 */
@Composable
fun AutoRefresh(
    everyMs: Long,
    onRefresh: () -> Unit,
) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latest by androidx.compose.runtime.rememberUpdatedState(onRefresh)
    androidx.compose.runtime.LaunchedEffect(owner, everyMs) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(everyMs)
                latest()
            }
        }
    }
}
