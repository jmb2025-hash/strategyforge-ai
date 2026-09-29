package app.strategyforge.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.compose.rememberNavController
import app.strategyforge.android.core.notify.DeepLinks
import app.strategyforge.android.core.notify.Route
import app.strategyforge.android.engine.EngineService
import app.strategyforge.android.platform.DeviceAuth
import app.strategyforge.android.platform.LocalConfig
import app.strategyforge.android.ui.MainShell
import app.strategyforge.android.ui.SessionViewModel
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.engine.api.EngineRuntime
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Compose app (section 7). Everything runs on the phone (D-027): the app opens
 * behind the device lock, starts the background engine and never contacts a server of its own.
 * Deep links only navigate; they never act.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var config: LocalConfig

    @Inject lateinit var runtime: EngineRuntime

    private var pendingRoute by mutableStateOf<Route?>(null)
    private var locked by mutableStateOf(false)
    private var backgroundedAt = 0L

    private val lockObserver =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) locked = lockRequired()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (config.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        pendingRoute = routeOf(intent)
        locked = savedInstanceState?.getBoolean(STATE_LOCKED) ?: lockRequired()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lockObserver)
        EngineService.start(this)
        setContent {
            SfTheme {
                if (locked) {
                    LockScreen(onUnlock = ::unlock)
                } else {
                    AppRoot(pendingRoute) { pendingRoute = null }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LOCKED, locked)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lockObserver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { pendingRoute = it }
    }

    private fun lockRequired() = config.appLock && DeviceAuth.available(this)

    private fun unlock() {
        DeviceAuth.prompt(this, "Unlock StrategyForge", "Use your fingerprint, face or screen lock", onSuccess = {
            locked = false
            // Unlocking counts as a recent confirmation for protected actions (D-029).
            runtime.host.post { runtime.engine.auth.confirmed() }
        }, onCancel = {})
    }

    private fun routeOf(intent: Intent?): Route? = intent?.data?.toString()?.let { DeepLinks.parse(it) }

    companion object {
        private const val STATE_LOCKED = "locked"
        private const val RELOCK_AFTER_MS = 5 * 60 * 1000L
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("StrategyForge is locked", style = MaterialTheme.typography.headlineSmall)
            Text("Paper trading keeps running in the background.", Modifier.padding(vertical = 8.dp))
            Button(onClick = onUnlock, modifier = Modifier.testTag("unlock")) { Text("Unlock") }
        }
    }
}

@Composable
fun AppRoot(
    pendingRoute: Route?,
    onRouteConsumed: () -> Unit,
) {
    val session: SessionViewModel = hiltViewModel()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        session.onStarted()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    MainShell(rememberNavController(), session, pendingRoute, onRouteConsumed)
}
