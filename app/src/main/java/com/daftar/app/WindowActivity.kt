package com.daftar.app

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.daftar.app.ui.LocalPaneNav
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Nav
import com.daftar.app.ui.RootPaneNav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.file
import com.daftar.app.ui.screenFor
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarTheme
import com.daftar.app.ui.widthClassOf
import com.daftar.app.ui.workspace.EXTRA_OPEN_PATH
import com.daftar.app.ui.workspace.EntryStackContent
import com.daftar.app.ui.workspace.ImportHost
import com.daftar.app.ui.workspace.WindowNav
import com.daftar.app.ui.workspace.openDropTarget
import java.io.File

/**
 * A separate app window showing one file (Samsung multi-window / pop-up view). Launched by
 * [com.daftar.app.ui.workspace.Workspace.openInNewWindow] as a new document task, so several windows can coexist with the main one.
 */
class WindowActivity : AppCompatActivity() {
    private var nav: WindowNav? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val f = intent.getStringExtra(EXTRA_OPEN_PATH)?.let(::File)
        val start = f?.takeIf { it.exists() }?.let { screenFor(it) }
        if (f == null || start == null) {
            android.widget.Toast.makeText(this, R.string.ws_window_missing, android.widget.Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val n = WindowNav(this, start)
        nav = n
        Nav.activePane = n
        setLabel(if (f.isDirectory) f.name else f.nameWithoutExtension)
        setContent {
            DaftarTheme {
                val width = widthClassOf(LocalConfiguration.current.screenWidthDp.dp)
                CompositionLocalProvider(LocalWidthClass provides width, LocalPaneNav provides n) {
                    BackHandler { n.back() }
                    // keep the task label in sync with what the window shows
                    val top = n.stack.current
                    LaunchedEffect(top) {
                        val label = top.file?.nameWithoutExtension ?: (top as? Screen.Library)?.dir?.let { File(it).name }
                        if (label != null) setLabel(label)
                    }
                    Box(Modifier.fillMaxSize().background(D.c.bg).openDropTarget(n)) { EntryStackContent(n.stack) }
                    ImportHost(n)
                }
            }
        }
    }

    private fun setLabel(label: String) {
        val td = if (Build.VERSION.SDK_INT >= 33) ActivityManager.TaskDescription.Builder().setLabel(label).build()
        else @Suppress("DEPRECATION") ActivityManager.TaskDescription(label)
        setTaskDescription(td)
    }

    private fun focusHere() {
        val n = nav ?: return
        if (!n.owns(Nav.activePane)) Nav.activePane = n
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Focus goes to this window; a split pane inside it overrides this during Compose dispatch.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) nav?.let { Nav.activePane = it }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() { super.onResume(); focusHere() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusHere()
    }

    override fun onDestroy() {
        nav?.let { if (it.owns(Nav.activePane)) Nav.activePane = RootPaneNav }
        super.onDestroy()
    }
}
