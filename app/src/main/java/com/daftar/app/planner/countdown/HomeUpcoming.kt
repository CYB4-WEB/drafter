package com.daftar.app.planner.countdown

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.planner.MINUTE
import com.daftar.app.planner.Planner
import com.daftar.app.ui.*
import com.daftar.app.ui.theme.D

/**
 * Home "Upcoming" in the countdown language: compact colour cards with the end block ("3 / days to go").
 * Tap → live countdown; "See all" → Countdowns. Ticks once a minute and only while Home is visible.
 */
@Composable
fun HomeUpcomingSection(limit: Int = 4) {
    val now = rememberTickingNow(MINUTE)
    val v = Planner.version
    val minute = now / MINUTE
    // Next occurrence per event (a weekly class appears once), ticked-off assignments left out.
    val items = remember(v, minute, limit) { futureCountdowns(Planner.all(), now).filter { !isDone(it.event) }.take(limit) }
    val subjects = rememberSubjectMap()
    SectionTitle(stringResource(R.string.upcoming)) {
        TextButton(onClick = { pane.push(Screen.Countdowns) }) { Text(stringResource(R.string.see_all)) }
    }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().card(D.c)) {
            EmptyState(Icons.Rounded.EventAvailable, stringResource(R.string.nothing_upcoming)) {
                OutlinedButton(onClick = { pane.push(Screen.EditEvent(null)) }) { Text(stringResource(R.string.add_event)) }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { o ->
            key(o.event.id, o.start) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    CountdownCard(o, subjects[o.event.id], now, compact = true, onLongClick = { menu = true }) {
                        pane.push(Screen.Countdown(o.event.id, o.start))
                    }
                    CardMenu(o, menu, { menu = false }) { pane.push(Screen.Countdown(o.event.id, o.start)) }
                }
            }
        }
    }
}
