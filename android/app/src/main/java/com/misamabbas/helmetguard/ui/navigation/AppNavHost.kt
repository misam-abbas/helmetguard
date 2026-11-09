package com.misamabbas.helmetguard.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.misamabbas.helmetguard.auth.AuthState
import com.misamabbas.helmetguard.ui.auth.AccountBlockedScreen
import com.misamabbas.helmetguard.ui.auth.AuthViewModel
import com.misamabbas.helmetguard.ui.auth.SignInScreen
import com.misamabbas.helmetguard.ui.auth.SplashScreen
import com.misamabbas.helmetguard.ui.home.HomeScreen
import com.misamabbas.helmetguard.ui.live.LiveScreen
import com.misamabbas.helmetguard.ui.sessions.SessionSummaryScreen
import com.misamabbas.helmetguard.ui.sessions.SessionsScreen
import com.misamabbas.helmetguard.ui.settings.DiagnosticsScreen
import com.misamabbas.helmetguard.ui.settings.SettingsScreen
import com.misamabbas.helmetguard.ui.settings.SyncCenterScreen
import com.misamabbas.helmetguard.ui.video.VideoAnalysisScreen
import com.misamabbas.helmetguard.ui.video.VideoSetupScreen
import com.misamabbas.helmetguard.ui.violations.ViolationDetailScreen
import com.misamabbas.helmetguard.ui.violations.ViolationsScreen

private object Routes {
    const val HOME = "home"
    const val DETECT = "detect"
    const val LIVE = "live"
    const val VIDEO_SETUP = "video_setup"
    const val VIDEO_RUN = "video_run/{id}"
    const val SUMMARY = "summary/{id}"
    const val VIOLATIONS = "violations"
    const val VIOLATION = "violation/{id}"
    const val SESSIONS = "sessions"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.DETECT, "Detect", Icons.Default.CameraAlt),
    Tab(Routes.VIOLATIONS, "Violations", Icons.AutoMirrored.Filled.List),
    Tab(Routes.SYNC, "Sync", Icons.Default.CloudUpload),
)

/** Gates the app on the account state, then shows the tabs. */
@Composable
fun AppRoot(auth: AuthViewModel = hiltViewModel()) {
    val state by auth.state.collectAsStateWithLifecycle()
    when (state) {
        null -> SplashScreen(auth)
        AuthState.SignedOut -> SignInScreen(auth)
        AuthState.AwaitingApproval -> AccountBlockedScreen("Waiting for approval", "An admin needs to approve your account before you can use HelmetGuard.", auth)
        AuthState.Disabled -> AccountBlockedScreen("This account is disabled", "Your saved records stay safely on this phone. Ask an admin if you think this is a mistake.", auth)
        AuthState.MustGoOnline -> AccountBlockedScreen("Please go online once", "This phone has been offline for too long. Connect to the internet so your account can be checked.", auth)
        is AuthState.Ready -> MainApp()
    }
}

@Composable
private fun MainApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    Scaffold(
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.goToTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onLive = { nav.navigate(Routes.LIVE) },
                    onVideo = { nav.navigate(Routes.VIDEO_SETUP) },
                    onSessions = { nav.navigate(Routes.SESSIONS) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                    onSync = { nav.goToTab(Routes.SYNC) },
                )
            }
            composable(Routes.DETECT) { DetectChooser(onLive = { nav.navigate(Routes.LIVE) }, onVideo = { nav.navigate(Routes.VIDEO_SETUP) }) }
            composable(Routes.LIVE) { LiveScreen() }
            composable(Routes.VIDEO_SETUP) {
                VideoSetupScreen(onStart = { id ->
                    nav.navigate("video_run/$id") { popUpTo(Routes.VIDEO_SETUP) { inclusive = true } }
                })
            }
            composable(Routes.VIDEO_RUN) { backStack ->
                val id = backStack.arguments?.getString("id").orEmpty()
                VideoAnalysisScreen(id, onFinished = {
                    nav.navigate("summary/$id") { popUpTo(Routes.VIDEO_RUN) { inclusive = true } }
                })
            }
            composable(Routes.SUMMARY) { backStack ->
                SessionSummaryScreen(backStack.arguments?.getString("id").orEmpty(), onOpenViolation = { nav.navigate("violation/$it") })
            }
            composable(Routes.VIOLATIONS) { ViolationsScreen(onOpen = { nav.navigate("violation/$it") }) }
            composable(Routes.VIOLATION) { backStack -> ViolationDetailScreen(backStack.arguments?.getString("id").orEmpty()) }
            composable(Routes.SESSIONS) {
                SessionsScreen(onOpen = { nav.navigate("summary/$it") }, onResume = { nav.navigate("video_run/$it") })
            }
            composable(Routes.SYNC) { SyncCenterScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen() }
        }
    }
}

private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun DetectChooser(onLive: () -> Unit, onVideo: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose how to detect", style = MaterialTheme.typography.headlineSmall)
            Button(onLive, Modifier.fillMaxWidth()) { Text("Live camera") }
            Button(onVideo, Modifier.fillMaxWidth()) { Text("Prerecorded video") }
        }
    }
}
