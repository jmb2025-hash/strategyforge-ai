package app.strategyforge.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.strategyforge.android.core.notify.DeepLinks
import app.strategyforge.android.core.notify.Route
import app.strategyforge.android.core.state.AccessStep
import app.strategyforge.android.platform.LocalConfig
import app.strategyforge.android.ui.AccessActions
import app.strategyforge.android.ui.AccessScreen
import app.strategyforge.android.ui.MainShell
import app.strategyforge.android.ui.SessionViewModel
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.deviceTimezone
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single-activity Compose app (section 7). Deep links only navigate; they never act. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var config: LocalConfig

    private var pendingRoute by mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (config.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        pendingRoute = routeOf(intent)
        setContent {
            SfTheme {
                AppRoot(pendingRoute) { pendingRoute = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { pendingRoute = it }
    }

    private fun routeOf(intent: Intent?): Route? = intent?.data?.toString()?.let { DeepLinks.parse(it) }
}

@Composable
fun AppRoot(
    pendingRoute: Route?,
    onRouteConsumed: () -> Unit,
) {
    val session: SessionViewModel = hiltViewModel()
    val access by session.access.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (access.step == AccessStep.SIGNED_IN) {
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            session.onSignedIn()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        MainShell(rememberNavController(), session, pendingRoute, onRouteConsumed)
    } else {
        val device = Build.MODEL.take(60)
        val p = session.access
        AccessScreen(
            access,
            AccessActions(
                connect = p::connect,
                bootstrap = { u, pw, c, t -> p.bootstrap(u, pw, c, device, deviceTimezone(), t) },
                login = { u, pw, totp -> p.login(u, pw, totp, device) },
                recover = { u, code, pw -> p.recover(u, code, pw, device) },
                showRecovery = p::showRecovery,
                showLogin = p::showLogin,
                codesSaved = p::recoveryCodesSaved,
                changeServer = p::changeServer,
            ),
        )
    }
}
