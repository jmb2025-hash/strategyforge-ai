package app.strategyforge.android

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.model.HistoryDownload
import app.strategyforge.android.ui.HistoryDownloadBanner
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.historyMinutes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-081 a stock plan's backtest waiting on the free data plan's limit shows its download progress. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HistoryDownloadUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the banner shows how many stocks are in and about how long the rest takes`() {
        rule.setContent { SfTheme { HistoryDownloadBanner(HistoryDownload(left = 19, total = 30)) } }
        rule.onNodeWithText("11 of 30 stocks in, 19 to go (about 3 min)", substring = true).assertExists()
        rule.onNodeWithText("runs again by itself", substring = true).assertExists()
    }

    @Test
    fun `download time is at least a minute, eight stocks a minute`() {
        assertEquals(1, historyMinutes(1))
        assertEquals(1, historyMinutes(8))
        assertEquals(4, historyMinutes(30))
    }
}
