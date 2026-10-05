package com.daftar.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.planner.Planner
import com.daftar.app.planner.countdown.CountdownCard
import com.daftar.app.planner.countdown.CountdownPrefs
import com.daftar.app.planner.countdown.futureCountdowns
import com.daftar.app.planner.countdown.isDone
import com.daftar.app.planner.countdown.rememberSubjectMap
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarTheme
import com.daftar.app.ui.widthClassOf

/** Configure (and reconfigure) a Countdown widget: next exam (default), next event, or one upcoming planner event. */
class CountdownWidgetConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        Planner.init(this)
        val current = CountdownPrefs.widgetEvent(this, widgetId)
        setContent {
            DaftarTheme {
                val cfg = LocalConfiguration.current
                CompositionLocalProvider(LocalWidthClass provides widthClassOf(cfg.screenWidthDp.dp)) {
                    Picker(current, ::pick) { finish() }
                }
            }
        }
    }

    private fun pick(eventId: Long) {
        CountdownPrefs.setWidgetEvent(this, widgetId, eventId)
        val m = AppWidgetManager.getInstance(this)
        CountdownWidgets.update(this, m, intArrayOf(widgetId), Planner.snapshot())
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun Picker(current: Long, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val c = D.c
    val now = remember { System.currentTimeMillis() }
    val v = Planner.version
    val items = remember(v) { futureCountdowns(Planner.all(), now).filter { !isDone(it.event) } }
    val subjects = rememberSubjectMap()
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = c.ink) }
            Text(stringResource(R.string.cd_widget_pick_title), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
        }
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Option(Icons.Rounded.Quiz, stringResource(R.string.cd_widget_next_exam), stringResource(R.string.cd_widget_next_exam_desc),
                    current == CountdownPrefs.WIDGET_NEXT_EXAM) { onPick(CountdownPrefs.WIDGET_NEXT_EXAM) }
            }
            item {
                Option(Icons.Rounded.Event, stringResource(R.string.cd_widget_next_any), stringResource(R.string.cd_widget_next_any_desc),
                    current == CountdownPrefs.WIDGET_NEXT_ANY) { onPick(CountdownPrefs.WIDGET_NEXT_ANY) }
            }
            if (items.isNotEmpty()) item {
                Text(stringResource(R.string.cd_widget_events), style = MaterialTheme.typography.titleMedium, color = c.ink,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            items(items, key = { it.event.id }) { o ->
                Box(if (o.event.id == current) Modifier.border(2.dp, c.accent, RoundedCornerShape(18.dp)).padding(3.dp) else Modifier) {
                    CountdownCard(o, subjects[o.event.id], now, compact = true) { onPick(o.event.id) }
                }
            }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) c.accent else c.muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent)
    }
}
