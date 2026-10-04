package com.daftar.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.data.Storage
import com.daftar.app.pdf.PdfScreen
import com.daftar.app.planner.EditEventScreen
import com.daftar.app.planner.PlannerScreen
import com.daftar.app.slides.SlidesScreen
import com.daftar.app.ui.*
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarTheme
import com.daftar.app.word.ImageScreen
import com.daftar.app.word.WordScreen
import java.io.File

class MainActivity : AppCompatActivity() {

    /** Pending one-shot UI action from a widget/notification, consumed by HomeScreen. */
    companion object {
        val pendingAction = mutableStateOf<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            DaftarTheme {
                val width = widthClassOf(LocalConfiguration.current.screenWidthDp.dp)
                CompositionLocalProvider(LocalWidthClass provides width) { AppShell() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra(EXTRA_ACTION)?.let { a ->
            when (a) {
                ACTION_PLANNER -> Nav.tab(Screen.Planner)
                ACTION_ADD_EVENT -> { Nav.tab(Screen.Planner); Nav.push(Screen.EditEvent(null)) }
                else -> { Nav.tab(Screen.Home); pendingAction.value = a }
            }
            i.removeExtra(EXTRA_ACTION)
        }
        if (i.hasExtra(EXTRA_EVENT_ID)) {
            val id = i.getLongExtra(EXTRA_EVENT_ID, 0)
            Nav.tab(Screen.Planner); Nav.push(Screen.EditEvent(id))
            i.removeExtra(EXTRA_EVENT_ID)
        }
        // Files shared / opened from other apps are copied into Inbox and opened.
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            Storage.import(this, uri, Storage.inbox())?.let { Nav.open(this, it) }
            i.action = null
        }
    }
}

private data class NavItem(val screen: Screen, val label: Int, val icon: ImageVector)

@Composable
private fun AppShell() {
    val current = Nav.current
    BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
    val items = listOf(
        NavItem(Screen.Home, R.string.home, Icons.Rounded.Home),
        NavItem(Screen.Library(Storage.root.absolutePath), R.string.files, Icons.Rounded.Folder),
        NavItem(Screen.Notes, R.string.notes, Icons.AutoMirrored.Rounded.StickyNote2),
        NavItem(Screen.Planner, R.string.planner, Icons.Rounded.CalendarMonth),
        NavItem(Screen.Search(""), R.string.search, Icons.Rounded.Search),
        NavItem(Screen.Settings, R.string.settings, Icons.Rounded.Settings),
    )
    fun selected(it: NavItem) = when (val s = Nav.stack.first()) {
        is Screen.Library -> it.screen is Screen.Library
        is Screen.Search -> it.screen is Screen.Search
        else -> it.screen == s
    }
    val showChrome = Nav.stack.size == 1 || current is Screen.Library
    val compact = LocalWidthClass.current == WidthClass.Compact

    Row(Modifier.fillMaxSize().background(D.c.bg)) {
        if (showChrome && !compact) Sidebar(items, ::selected)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { s -> Route(s) }
            }
            if (showChrome && compact) {
                NavigationBar(containerColor = D.c.surface, tonalElevation = 0.dp) {
                    // phone: Home, Files, Notes, Planner, More(settings)
                    items.filter { it.screen !is Screen.Search }.forEach { item ->
                        val isMore = item.screen is Screen.Settings
                        NavigationBarItem(
                            selected = selected(item),
                            onClick = { Nav.tab(item.screen) },
                            icon = { Icon(if (isMore) Icons.Rounded.MoreHoriz else item.icon, null) },
                            label = { Text(stringResource(if (isMore) R.string.more else item.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = D.c.accent, selectedTextColor = D.c.accent,
                                indicatorColor = D.c.accent.copy(alpha = 0.12f),
                                unselectedIconColor = D.c.muted, unselectedTextColor = D.c.muted,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(items: List<NavItem>, selected: (NavItem) -> Boolean) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().background(D.c.bg).windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = D.c.ink,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 24.dp))
        items.forEach { item ->
            val sel = selected(item)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .background(if (sel) D.c.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { Nav.tab(item.screen) }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, null, tint = if (sel) D.c.accent else D.c.muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(item.label), style = MaterialTheme.typography.labelLarge, color = if (sel) D.c.accent else D.c.ink)
            }
        }
    }
}

@Composable
fun Route(s: Screen) {
    when (s) {
        Screen.Home -> HomeScreen()
        is Screen.Library -> LibraryScreen(s.dir)
        Screen.Notes -> NotesScreen()
        Screen.Planner -> PlannerScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.Search -> SearchScreen(s.query)
        is Screen.Note -> NoteScreen(s.path)
        is Screen.Pdf -> PdfScreen(s.path)
        is Screen.Slides -> SlidesScreen(s.path)
        is Screen.Word -> WordScreen(s.path)
        is Screen.Image -> ImageScreen(s.path)
        is Screen.EditEvent -> EditEventScreen(s.id, s.presetType)
        is Screen.Web -> WebScreen(s.url)
        is Screen.Convert -> com.daftar.app.convert.ConvertScreen(s.path)
        is Screen.Split -> SplitScreen(s)
    }
}

@Suppress("unused")
private fun exists(p: String) = File(p).exists()
