package app.strategyforge.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.format.PnlDirection
import app.strategyforge.android.core.state.ActionState
import java.time.Instant
import java.time.ZoneId

// Colours meet WCAG AA contrast on their backgrounds; P/L never relies on colour alone (NFR-009).
private val Navy = Color(0xFF102A43)
private val Mint = Color(0xFF1B7F52)
private val Loss = Color(0xFFB3261E)

@Composable
fun SfTheme(content: @Composable () -> Unit) {
    val scheme =
        if (isSystemInDarkTheme()) {
            darkColorScheme(primary = Color(0xFF9CD8BA), secondary = Color(0xFFB8C7D9))
        } else {
            lightColorScheme(primary = Navy, secondary = Mint)
        }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun SfCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
fun LabelValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
            PnlDirection.GAIN -> Mint
            PnlDirection.LOSS -> Loss
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) { contentDescription = "$label: ${p.contentDescription}" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
    val (bg, prefix) =
        when (kind) {
            BannerKind.INFO -> MaterialTheme.colorScheme.secondaryContainer to "Info"
            BannerKind.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to "Warning"
            BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer to "Error"
        }
    Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$prefix: $text", modifier = Modifier.padding(10.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("banner"), style = MaterialTheme.typography.bodyMedium)
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
    content: @Composable (T) -> Unit,
) {
    when (resource) {
        Resource.Loading -> Loading()
        is Resource.Failure -> ErrorState(resource.error, onRetry)
        is Resource.Data ->
            Column {
                FreshnessBanner(resource, fmt)
                if (empty(resource.value)) Text(emptyText, modifier = Modifier.padding(16.dp).testTag("empty")) else content(resource.value)
            }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading" })
    }
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
    when (action) {
        ActionState.Idle -> Unit
        ActionState.Running -> Loading()
        is ActionState.Done -> Banner(action.message)
        is ActionState.Failed ->
            Column {
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
    Surface(
        color =
            when (kind) {
                BannerKind.INFO -> MaterialTheme.colorScheme.surfaceVariant
                BannerKind.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer
            },
        shape = MaterialTheme.shapes.small,
    ) { Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium) }
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
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
