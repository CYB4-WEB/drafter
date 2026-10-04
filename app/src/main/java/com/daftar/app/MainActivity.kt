package com.daftar.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.daftar.app.ui.workspace.ImportHost
import com.daftar.app.ui.workspace.ImportSource
import com.daftar.app.ui.workspace.Importer
import com.daftar.app.ui.workspace.SplitPane
import com.daftar.app.ui.workspace.WindowNav
import com.daftar.app.word.ImageScreen
import com.daftar.app.word.WordScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                val cfg = LocalConfiguration.current
                val width = widthClassOf(cfg.screenWidthDp.dp)
                CompositionLocalProvider(LocalWidthClass provides width) {
                    AppShell(tiny = cfg.screenWidthDp < 400)
                    ImportHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Touching the main window gives it focus; a split pane overrides this during Compose dispatch. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && Nav.activePane !== RootPaneNav) Nav.activePane = RootPaneNav
        return super.dispatchTouchEvent(ev)
    }

    /** Coming back from another Daftar window (keyboard / recents) without touching: take focus back from it. */
    private fun reclaimFocus() {
        val ap = Nav.activePane
        val foreign = ap is WindowNav || (ap is SplitPane && ap.controller.host is WindowNav)
        if (foreign) Nav.activePane = RootPaneNav
    }

    override fun onResume() { super.onResume(); reclaimFocus() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reclaimFocus()
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
        // Files shared / opened from other apps are copied into Inbox (on IO, with progress) and opened.
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                ) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            val inbox = Storage.inbox()
            Importer.start(this, ImportSource.Docs(uris), inbox, move = false) { res ->
                when {
                    res.files.size == 1 -> Nav.open(this, res.files[0])
                    res.files.size > 1 -> Nav.push(Screen.Library(inbox.absolutePath))
                }
            }
            i.action = null
            return
        }
        // Plain text shared without a file: a link opens in the browser, other text is kept as a .txt in Inbox.
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!text.isNullOrEmpty()) {
                val link = text.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains(Regex("\\s")) }
                if (link != null) Nav.push(Screen.Web(link))
                else {
                    val name = i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
                        ?: (getString(R.string.ws_shared_text) + " " + SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(Date()))
                    runCatching { Storage.writeText(Storage.inbox(), name, text) }.getOrNull()?.let { Nav.open(this, it) }
                }
            }
            i.action = null
        }
    }
}

private data class NavItem(val screen: Screen, val label: Int, val icon: ImageVector)

private val ItemHome = NavItem(Screen.Home, R.string.home, Icons.Rounded.Home)
private val ItemNotes = NavItem(Screen.Notes, R.string.notes, Icons.AutoMirrored.Rounded.StickyNote2)
private val ItemPlanner = NavItem(Screen.Planner, R.string.planner, Icons.Rounded.CalendarMonth)
private val ItemStudy = NavItem(Screen.Study, R.string.study, Icons.Rounded.School)
private val ItemConvert = NavItem(Screen.Convert(), R.string.convert, Icons.Rounded.Transform)
private val ItemSearch = NavItem(Screen.Search(""), R.string.search, Icons.Rounded.Search)
private val ItemSettings = NavItem(Screen.Settings, R.string.settings, Icons.Rounded.Settings)

/** Top-level destination a stack root belongs to (for highlighting). */
private fun sameDestination(item: Screen, root: Screen): Boolean = when (root) {
    is Screen.Library -> item is Screen.Library
    is Screen.Search -> item is Screen.Search
    is Screen.Convert -> item is Screen.Convert
    else -> item == root
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(tiny: Boolean) {
    val current = Nav.current
    BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
    val itemFiles = NavItem(Screen.Library(Storage.root.absolutePath), R.string.files, Icons.Rounded.Folder)
    val sideItems = listOf(ItemHome, itemFiles, ItemNotes, ItemPlanner, ItemStudy, ItemConvert, ItemSearch, ItemSettings)
    val barItems = listOf(ItemHome, itemFiles, ItemNotes, ItemPlanner)
    val moreItems = listOf(ItemStudy, ItemSearch, ItemConvert, ItemSettings)
    val root = Nav.stack.first()
    fun selected(it: NavItem) = sameDestination(it.screen, root)
    val split = current is Screen.Split
    val showChrome = !split && (Nav.stack.size == 1 || current is Screen.Library)
    val compact = LocalWidthClass.current == WidthClass.Compact
    var more by remember { mutableStateOf(false) }
    val c = D.c

    Row(Modifier.fillMaxSize().background(c.bg)) {
        if (showChrome && !compact && !tiny) Sidebar(sideItems, ::selected)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { s -> Route(s) }
            }
            if (showChrome && (compact || tiny)) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.accent, selectedTextColor = c.accent,
                    indicatorColor = c.accent.copy(alpha = 0.12f),
                    unselectedIconColor = c.muted, unselectedTextColor = c.muted,
                )
                NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                    barItems.forEach { item ->
                        NavigationBarItem(
                            selected = selected(item), onClick = { Nav.tab(item.screen) },
                            icon = { Icon(item.icon, null) }, label = { Text(stringResource(item.label), maxLines = 1) }, colors = colors,
                        )
                    }
                    NavigationBarItem(
                        selected = moreItems.any { selected(it) }, onClick = { more = true },
                        icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text(stringResource(R.string.more), maxLines = 1) }, colors = colors,
                    )
                }
            }
        }
    }

    if (more) {
        ModalBottomSheet(onDismissRequest = { more = false }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.more), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(8.dp))
                moreItems.forEach { item ->
                    val sel = selected(item)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(if (sel) c.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { more = false; Nav.tab(item.screen) }.padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(item.icon, null, tint = if (sel) c.accent else c.muted)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(item.label), color = if (sel) c.accent else c.ink, style = MaterialTheme.typography.bodyLarge)
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
        DaftarBrand(markSize = 28.dp, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 24.dp))
        items.forEach { item ->
            val sel = selected(item)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .background(if (sel) D.c.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(12.dp))
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
        Screen.Study -> com.daftar.app.study.StudyScreen()
        is Screen.Review -> com.daftar.app.study.ReviewScreen(s.deck)
        Screen.Trash -> com.daftar.app.ui.TrashScreen()
    }
}
