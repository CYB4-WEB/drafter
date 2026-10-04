package com.daftar.app.ui.workspace

import android.app.Activity
import android.content.Context
import com.daftar.app.ui.PaneNav
import com.daftar.app.ui.Screen
import java.io.File

/** Extra with the absolute path of the file a separate window opens. */
const val EXTRA_OPEN_PATH = "daftar.open_path"

/**
 * Navigation of a separate app window ([com.daftar.app.WindowActivity]): its own back stack; back at the root closes the window.
 * The main window's [com.daftar.app.ui.Nav] stack is not affected.
 */
class WindowNav(private val activity: Activity, initial: Screen) : PaneNav {
    val stack = EntryStack(initial)
    override val screens: List<Screen> get() = stack.screens
    override fun back() { if (!stack.pop()) activity.finish() }
    override fun open(ctx: Context, f: File) = stack.open(ctx, f)
    override fun push(s: Screen) = stack.push(s)
    override fun replace(s: Screen) = stack.replace(s)
    override fun popTo(s: Screen) = stack.popTo(s)

    /** True when [nav] is this window or one of the split panes it hosts. */
    fun owns(nav: PaneNav): Boolean = nav === this || (nav is SplitPane && nav.controller.host === this)
}
