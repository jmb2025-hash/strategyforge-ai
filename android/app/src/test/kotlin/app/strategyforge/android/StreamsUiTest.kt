package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.model.StreamInfo
import app.strategyforge.android.core.model.StreamsInfo
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.StreamsSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-056 live price streaming: status per stream and the Alpaca key entry. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StreamsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `stream status is shown and both key parts are saved together`() {
        val saved = mutableListOf<Pair<String?, String?>>()
        val info =
            StreamsInfo(
                stockKeyConfigured = false,
                streams =
                    listOf(
                        StreamInfo("Coinbase", "CRYPTO", "LIVE", symbols = 3, subscribed = 3),
                        StreamInfo("Alpaca IEX", "US_EQUITY", "ERROR", symbols = 2, error = "Alpaca: auth failed (402)"),
                    ),
            )
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    StreamsSection(info, live = true, onStreamKey = { a, b -> saved += a to b }, onOpenUrl = {})
                }
            }
        }
        rule.onNodeWithText("Streaming 3 symbols").assertExists()
        rule.onNodeWithText("Reconnecting").assertExists()
        rule.onNodeWithText("Alpaca: auth failed (402)").assertExists()
        rule
            .onAllNodesWithText("Alpaca API key ID")
            .onFirst()
            .performScrollTo()
            .performTextInput("PKTESTKEYID123")
        rule
            .onAllNodesWithText("Alpaca secret key")
            .onLast()
            .performScrollTo()
            .performTextInput("test-secret-value-0123456789")
        rule.onNodeWithTag("save-stream-key").performScrollTo().performClick()
        assertEquals(listOf<Pair<String?, String?>>("PKTESTKEYID123" to "test-secret-value-0123456789"), saved)
    }
}
