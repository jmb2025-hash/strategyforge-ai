package app.strategyforge.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.Compilation
import app.strategyforge.android.core.model.ResearchDetail
import app.strategyforge.android.core.model.ResearchRun
import app.strategyforge.android.core.model.ResearchSession
import app.strategyforge.android.core.state.ActionState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ------------------------------------------------------------------ research list and new research (D-034)

@Composable
fun ResearchListScreen(
    vm: ResearchListViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val started by vm.started.collectAsStateWithLifecycle()
    val limit by vm.limit.collectAsStateWithLifecycle()
    LaunchedEffect(started) {
        started?.let {
            vm.consumeStarted()
            nav.navigate("research/$it")
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        NewResearch(action is ActionState.Running, limit, onStart = vm::start)
        ActionFeedback(action)
        SectionTitle("Your research")
        ResourceContent(state, fmt, vm::refresh, empty = { it.isEmpty() }, emptyText = "No research yet.", art = Art.RESEARCH) { list ->
            list.forEach { r -> ResearchRow(r, fmt) { nav.navigate("research/${r.id}") } }
        }
    }
}

@Composable
fun NewResearch(
    busy: Boolean,
    limit: Int? = null,
    onStart: (message: String, assetClass: String) -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    var asset by rememberSaveable { mutableStateOf("CRYPTO") }
    Column {
        NewResearchContent(message, { message = it }, asset, { asset = it }, busy, limit) {
            onStart(message, asset)
            message = ""
        }
    }
}

@Composable
private fun NewResearchContent(
    message: String,
    onMessage: (String) -> Unit,
    asset: String,
    onAsset: (String) -> Unit,
    busy: Boolean,
    limit: Int?,
    onStart: () -> Unit,
) {
    SectionTitle("Research an investor or strategy")
    Text(
        "Describe who or what to research, for example \"Research the crypto trading strategies used by the group Chart Champions\". " +
            "The AI finds what they trade, their timeframes and their entry and exit rules, and you can keep asking follow-up questions. " +
            "When you are happy with it, turn the research into a strategy.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.padding(vertical = 4.dp)) {
        listOf("CRYPTO" to "Crypto", "US_EQUITY" to "Stocks").forEach { (a, label) ->
            FilterChip(selected = asset == a, onClick = { onAsset(a) }, label = { Text(label) }, modifier = Modifier.testTag("asset-$a"))
            Spacer(Modifier.width(8.dp))
        }
    }
    Field("What should the AI research?", message, onMessage, singleLine = false, modifier = Modifier.testTag("research-message"))
    CharCount(message.trim().length, limit)
    Button(
        onClick = onStart,
        enabled = message.isNotBlank() && !busy && fits(message, limit),
        modifier = Modifier.fillMaxWidth().testTag("start-research"),
    ) { Text("Start research") }
}

@Composable
private fun ResearchRow(
    r: ResearchSession,
    fmt: Formatters,
    onOpen: () -> Unit,
) {
    SfCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (r.status == "RUNNING") StatusChip("RUNNING") else StatusChip(r.reviewStatus)
        }
        Text(
            "${if (r.assetClass == "CRYPTO") "Crypto" else "Stocks"} · ${r.providerType.lowercase().replaceFirstChar { it.uppercase() }} · ${fmt.dateTime(r.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ------------------------------------------------------------------ conversation

@Composable
fun ResearchDetailScreen(
    vm: ResearchDetailViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val detail by vm.detail.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val limit by vm.limit.collectAsStateWithLifecycle()
    var confirmCompile by rememberSaveable { mutableStateOf(false) }
    val d = detail
    if (d == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Loading()
            ActionFeedback(action)
        }
        return
    }
    ResearchConversation(
        d,
        fmt,
        action,
        onSend = vm::send,
        onRetry = vm::retry,
        onCompile = { confirmCompile = true },
        onOpenStrategy = { nav.navigate("strategy/$it") },
        limit = limit,
    )
    if (confirmCompile) {
        ConfirmDialog(
            "Turn this research into a strategy?",
            "This confirms you have reviewed the research. The AI converts the whole conversation into strategy rules, and the app checks them " +
                "like any imported strategy. Backtest the strategy before activating it; all trading is simulated.",
            "Build strategy",
            onDismiss = { confirmCompile = false },
        ) {
            confirmCompile = false
            vm.compile()
        }
    }
}

@Composable
fun ResearchConversation(
    d: ResearchDetail,
    fmt: Formatters,
    action: ActionState,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onCompile: () -> Unit,
    onOpenStrategy: (String) -> Unit,
    limit: Int? = null,
) {
    val running = d.session.status == "RUNNING"
    val research = d.runs.filter { it.purpose == "RESEARCH" }
    val answered = research.any { it.status == "SUCCEEDED" }
    var reply by rememberSaveable(d.session.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle(d.session.title)
        Text(
            "${if (d.session.assetClass == "CRYPTO") "Crypto" else "Stocks"} research with ${d.session.providerType.lowercase().replaceFirstChar { it.uppercase() }} (${d.session.model})",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!d.session.conversation && research.firstOrNull()?.ownerMessage == null) {
            // Research created with the older form: its question stands in for the first message.
            Bubble(owner = true) { Text(d.session.prompt) }
        }
        research.forEach { r -> Turn(r, fmt) }
        if (running) {
            Row(Modifier.padding(vertical = 8.dp).testTag("thinking"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text(if (d.runs.lastOrNull()?.purpose == "COMPILE") "Building the strategy…" else "The AI is researching… this can take a minute.")
            }
        } else if (research.lastOrNull()?.status == "FAILED") {
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text("Try again") }
        }
        Field("Reply or ask a follow-up question", reply, { reply = it }, singleLine = false, modifier = Modifier.testTag("reply"))
        CharCount(reply.trim().length, limit)
        Button(
            onClick = {
                onSend(reply)
                reply = ""
            },
            enabled = reply.isNotBlank() && !running && action !is ActionState.Running && fits(reply, limit),
            modifier = Modifier.fillMaxWidth().testTag("send"),
        ) { Text("Send") }
        ActionFeedback(action)
        SectionTitle("Turn it into a strategy")
        Text(
            "When the research describes the strategy you want, the AI converts the whole conversation into rules the app can test and run. " +
                "Anything it cannot express is listed in the strategy's description.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onCompile, enabled = answered && !running, modifier = Modifier.fillMaxWidth().testTag("compile")) { Text("Compile research into a strategy") }
        d.compilations.asReversed().forEach { c -> CompilationCard(c, fmt, onOpenStrategy) }
        Banner(d.disclaimer)
    }
}

@Composable
private fun Turn(
    r: ResearchRun,
    fmt: Formatters,
) {
    r.ownerMessage?.let { Bubble(owner = true) { Text(it) } }
    Bubble(owner = false) {
        when (r.status) {
            "RUNNING" -> Text("…", style = MaterialTheme.typography.bodySmall)
            "FAILED" -> Banner(failureText(r), BannerKind.ERROR)
            else -> {
                Text("AI research · unverified · ${fmt.dateTime(r.startedAt)}", style = MaterialTheme.typography.labelSmall)
                Text(r.responseText.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                if (r.sources.isNotEmpty()) Sources(r)
            }
        }
    }
}

private fun failureText(r: ResearchRun): String =
    when (r.failureCode) {
        "RATE_LIMITED" -> "The AI provider's rate limit or free quota was reached. Wait a minute and try again."
        "AUTHENTICATION" -> "The AI provider rejected the key. Check it in More → AI providers and keys."
        "TIMEOUT" -> "The AI took too long to answer. Try again."
        "TRUNCATED" -> "The answer was too long and got cut off. Ask for a shorter answer, or raise the output limit in More → AI budget."
        "INTERRUPTED" -> "The app stopped while the AI was answering. Try again."
        else -> "${r.failureCode}: ${r.failureDetail ?: "the request failed"}"
    }

@Composable
private fun Sources(r: ResearchRun) {
    val context = LocalContext.current
    Text("Sources", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    r.sources.forEach { s ->
        Text(
            "• ${s.title ?: s.url}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
                    } catch (e: ActivityNotFoundException) {
                        // No browser installed; the source title is still shown.
                    }
                },
        )
    }
}

@Composable
private fun Bubble(
    owner: Boolean,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (owner) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (owner) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(if (owner) 0.85f else 1f),
        ) { Column(Modifier.padding(12.dp)) { content() } }
    }
}

@Composable
private fun CompilationCard(
    c: Compilation,
    fmt: Formatters,
    onOpenStrategy: (String) -> Unit,
) {
    SfCard(Modifier.testTag("compilation")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt.dateTime(c.createdAt), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            StatusChip(c.status)
        }
        when (c.status) {
            "COMPILED" -> Text("Strategy created and validated. Open it to backtest and activate it.")
            "MANUAL_REVIEW_REQUIRED" -> Text("Strategy created, but parts need your review before it can be used.")
            else -> Text("The research could not be turned into a valid strategy.")
        }
        issueMessages(c).take(MAX_ISSUES).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        c.strategyId?.let { id -> TextButton(onClick = { onOpenStrategy(id) }, modifier = Modifier.testTag("open-strategy")) { Text("Open strategy") } }
    }
}

private fun issueMessages(c: Compilation): List<String> =
    (c.issues as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull }
        .orEmpty()

private const val MAX_ISSUES = 5

private fun fits(
    text: String,
    limit: Int?,
) = limit == null || text.trim().length <= limit

/** "1,234 / 49,800 characters", in the error colour with advice once a message is too long (D-040). */
@Composable
fun CharCount(
    length: Int,
    limit: Int?,
) {
    if (limit == null || length == 0) return
    val over = length > limit
    Text(
        String.format(java.util.Locale.US, "%,d / %,d characters", length, limit) +
            if (over) " — too long: shorten it or raise the maximum input characters in More → AI budget" else "",
        style = MaterialTheme.typography.labelMedium,
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp).testTag("char-count"),
    )
}
