package com.haessentz.videoeditor

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.ui.AppTheme
import com.haessentz.videoeditor.ui.HomeScreen
import com.haessentz.videoeditor.ui.ModelsScreen
import com.haessentz.videoeditor.ui.ProjectScreen
import com.haessentz.videoeditor.ui.ShortEditorScreen

sealed interface Screen {
    data object Home : Screen
    data object Models : Screen
    data class Project(val id: String) : Screen
    data class ShortEditor(val projectId: String, val shortId: String) : Screen
}

class Nav(private val push: (Screen) -> Unit, private val pop: () -> Unit) {
    fun go(s: Screen) = push(s)
    fun back() = pop()
}

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun AppRoot() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val nav = remember {
        Nav(push = { s -> stack = stack + s }, pop = { if (stack.size > 1) stack = stack.dropLast(1) })
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }

    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.haessentz.videoeditor.work.Jobs.resumeInterrupted(ctx) }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(nav)
        Screen.Models -> ModelsScreen(nav)
        is Screen.Project -> ProjectScreen(s.id, nav)
        is Screen.ShortEditor -> ShortEditorScreen(s.projectId, s.shortId, nav)
    }
}
