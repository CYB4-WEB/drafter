package com.daftar.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.workspace.SplitWorkspace

private val VideoExt = Regex("""\.(mp4|m4v|webm|mov|3gp|ogv)$""", RegexOption.IGNORE_CASE)
private val WebSchemes = setOf("http", "https", "about", "data", "blob", "javascript")

/** Turns what the user typed into a URL: adds https:// to bare addresses, searches the web for anything else. */
internal fun normalizeWebInput(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    val scheme = Uri.parse(t).scheme?.lowercase()
    return when {
        scheme == "file" || scheme == "content" -> null
        scheme in WebSchemes -> t
        scheme != null && !t.contains(' ') && (t.startsWith("$scheme:") && !t.substringAfter(':').all { it.isDigit() || it == '/' }) -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> "https://www.google.com/search?q=" + Uri.encode(t)
    }
}

private fun isDirectVideo(url: String): Boolean = runCatching { VideoExt.containsMatchIn(Uri.parse(url).path ?: "") }.getOrDefault(false)

private fun videoPage(url: String): String {
    val src = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;height:100%;background:#000}video{width:100%;height:100%;object-fit:contain}</style></head>" +
        "<body><video src=\"$src\" controls autoplay playsinline></video></body></html>"
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Hands a non-web link (mailto:, tel:, intent:, market:…) to the system. Returns true when something handled it. */
private fun launchExternal(ctx: Context, url: String, view: WebView?): Boolean {
    return try {
        if (url.startsWith("intent:", true)) {
            val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            }
            try { ctx.startActivity(i); true }
            catch (_: ActivityNotFoundException) {
                val fallback = i.getStringExtra("browser_fallback_url")
                if (fallback != null && view != null && (fallback.startsWith("http://") || fallback.startsWith("https://"))) { view.loadUrl(fallback); true }
                else false
            }
        } else {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        }
    } catch (_: Exception) { false }
}

/**
 * In-app browser / video player for links placed in notes. Back goes back in page history first, then closes the screen.
 * Local files (file://, content://) are never loaded; other apps' schemes are handed to the system.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String) {
    val ctx = LocalContext.current
    val c = D.c
    val dark = c.dark
    val compact = LocalWidthClass.current == WidthClass.Compact
    val focus = LocalFocusManager.current
    val start = remember(url) { normalizeWebInput(url) }

    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(start ?: url) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(start == null) }
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(current)) }
    var menu by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    var overlay by remember { mutableStateOf<FrameLayout?>(null) }

    val blockedMsg = stringResource(R.string.ws_web_file_blocked)
    val noApp = stringResource(R.string.ws_web_no_app)

    fun hideFullscreen(notify: Boolean) {
        val act = ctx.findActivity()
        overlay?.let { o -> (o.parent as? ViewGroup)?.removeView(o); o.removeAllViews() }
        overlay = null
        if (act != null) WindowCompat.getInsetsController(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        val cb = fullscreen?.second
        fullscreen = null
        if (notify) runCatching { cb?.onCustomViewHidden() }
    }

    fun showFullscreen(v: View, cb: WebChromeClient.CustomViewCallback) {
        val act = ctx.findActivity()
        if (act == null) { cb.onCustomViewHidden(); return }
        if (fullscreen != null) hideFullscreen(true)
        val frame = FrameLayout(act).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        (act.window.decorView as ViewGroup).addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        overlay = frame
        fullscreen = v to cb
    }

    /** true = handled outside the WebView (blocked, external app or video wrapper). */
    fun route(view: WebView?, target: String): Boolean {
        val scheme = Uri.parse(target).scheme?.lowercase() ?: return false
        return when {
            scheme == "file" || scheme == "content" -> { toast(ctx, blockedMsg); true }
            (scheme == "http" || scheme == "https") && isDirectVideo(target) -> {
                view?.loadDataWithBaseURL(target, videoPage(target), "text/html", "utf-8", target); true
            }
            scheme in WebSchemes -> false
            else -> { if (!launchExternal(ctx, target, view)) toast(ctx, noApp); true }
        }
    }

    fun load(target: String) {
        val wv = web ?: return
        error = false
        if (!route(wv, target)) wv.loadUrl(target)
    }

    BackHandler(enabled = fullscreen != null) { hideFullscreen(true) }
    BackHandler(enabled = fullscreen == null && canBack) { web?.goBack() }
    DisposableEffect(Unit) { onDispose { if (fullscreen != null) hideFullscreen(true) } }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // ---- top bar ----
        Row(
            Modifier.fillMaxWidth().background(c.surface).windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pane.back() }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.ink) }
            IconButton(onClick = { web?.goBack() }, enabled = canBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ws_web_back), tint = if (canBack) c.ink else c.line)
            }
            if (!compact) {
                IconButton(onClick = { web?.goForward() }, enabled = canForward) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.ws_web_forward), tint = if (canForward) c.ink else c.line)
                }
                IconButton(onClick = { if (loading) web?.stopLoading() else { error = false; web?.reload() } }) {
                    Icon(if (loading) Icons.Rounded.Close else Icons.Rounded.Refresh, stringResource(if (loading) R.string.ws_web_stop else R.string.ws_web_reload), tint = c.ink)
                }
            }
            // address field
            Row(
                Modifier.weight(1f).height(40.dp).background(c.surfaceAlt, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (current.startsWith("https://")) Icons.Rounded.Lock else Icons.Rounded.Public, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    val shown = if (editing) field else TextFieldValue(current)
                    if (editing && field.text.isEmpty()) Text(stringResource(R.string.ws_web_url_hint), color = c.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    BasicTextField(
                        value = shown,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = {
                            val u = normalizeWebInput(field.text)
                            if (u == null) toast(ctx, blockedMsg) else { blocked = false; load(u) }
                            focus.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (it.isFocused && !editing) { editing = true; field = TextFieldValue(current, TextRange(0, current.length)) }
                            else if (!it.isFocused) editing = false
                        },
                    )
                }
            }
            if (!compact) {
                IconButton(onClick = { if (!launchExternal(ctx, current, null)) toast(ctx, noApp) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.ws_web_open_external), tint = c.ink)
                }
                IconButton(onClick = { shareText(ctx, current) }) { Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = c.ink) }
            } else Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.ws_web_forward)) }, { menu = false; web?.goForward() }, enabled = canForward,
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.muted) })
                    DropdownMenuItem({ Text(stringResource(if (loading) R.string.ws_web_stop else R.string.ws_web_reload)) },
                        { menu = false; if (loading) web?.stopLoading() else { error = false; web?.reload() } },
                        leadingIcon = { Icon(if (loading) Icons.Rounded.Close else Icons.Rounded.Refresh, null, tint = c.muted) })
                    DropdownMenuItem({ Text(stringResource(R.string.ws_web_open_external)) }, { menu = false; if (!launchExternal(ctx, current, null)) toast(ctx, noApp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.muted) })
                    DropdownMenuItem({ Text(stringResource(R.string.share)) }, { menu = false; shareText(ctx, current) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null, tint = c.muted) })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.line)) {
            if (loading && progress < 100) LinearProgressIndicator(
                progress = { progress / 100f }, modifier = Modifier.fillMaxSize(), color = c.accent, trackColor = c.line, drawStopIndicator = {},
            )
        }

        // ---- page ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (blocked && web == null) {
                EmptyState(Icons.Rounded.Public, blockedMsg, Modifier.align(Alignment.Center))
            } else {
                AndroidView(
                    factory = { context ->
                        val themed = android.view.ContextThemeWrapper(
                            context, if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
                        )
                        WebView(themed).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setBackgroundColor(if (dark) android.graphics.Color.rgb(0x14, 0x16, 0x1A) else android.graphics.Color.WHITE)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mediaPlaybackRequiresUserGesture = false
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                setSupportMultipleWindows(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    route(view, request.url.toString())

                                override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                                    loading = true; error = false
                                    if (u != null && !u.startsWith("data:")) current = u
                                }

                                override fun onPageFinished(view: WebView, u: String?) {
                                    loading = false
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                }

                                override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                                    if (u != null && !u.startsWith("data:")) current = u
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                    if (request.isForMainFrame) { error = true; loading = false }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                override fun onShowCustomView(view: View, callback: CustomViewCallback) { showFullscreen(view, callback) }
                                override fun onHideCustomView() { hideFullscreen(false) }
                                override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            }
                            web = this
                            if (start != null) { if (!route(this, start)) loadUrl(start) }
                        }
                    },
                    update = { wv ->
                        if (Build.VERSION.SDK_INT >= 33) wv.settings.isAlgorithmicDarkeningAllowed = dark
                        else if (Build.VERSION.SDK_INT >= 29) {
                            @Suppress("DEPRECATION")
                            wv.settings.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.webChromeClient = null
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                        if (web === wv) web = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (error) Column(
                    Modifier.fillMaxSize().background(c.bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(Icons.Rounded.Public, stringResource(R.string.ws_web_error)) {
                        Button(onClick = { error = false; web?.reload() }) { Text(stringResource(R.string.ws_web_reload)) }
                    }
                }
            }
        }
    }
}

private fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try { ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.share))) } catch (_: Exception) {}
}

/** Two panes side by side (split workspace, see ui/workspace/Split.kt). */
@Composable
fun SplitScreen(s: Screen.Split) = SplitWorkspace(s)
