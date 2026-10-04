package app.strategyforge.android

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.model.StreamInfo
import app.strategyforge.android.core.model.StreamsInfo
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.StreamsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-056 live price streaming: the state of each stream, with no keys to enter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StreamsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `each stream shows its state and error`() {
        val info =
            StreamsInfo(
                listOf(
                    StreamInfo("Coinbase", "CRYPTO", "LIVE", symbols = 3, subscribed = 3),
                    StreamInfo("Yahoo Finance", "US_EQUITY", "ERROR", symbols = 2, error = "Connection failed: HTTP 403"),
                ),
            )
        rule.setContent { SfTheme { StreamsSection(info, live = true) } }
        rule.onNodeWithText("Crypto · Coinbase").assertExists()
        rule.onNodeWithText("Streaming 3 symbols").assertExists()
        rule.onNodeWithText("Stocks and TSX · Yahoo Finance").assertExists()
        rule.onNodeWithText("Reconnecting").assertExists()
        rule.onNodeWithText("Connection failed: HTTP 403").assertExists()
    }
}
