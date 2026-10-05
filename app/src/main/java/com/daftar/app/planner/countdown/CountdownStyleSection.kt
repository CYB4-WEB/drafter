package com.daftar.app.planner.countdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.planner.PlanEvent
import com.daftar.app.ui.Chip
import com.daftar.app.ui.Screen
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.FolderPalette

/**
 * Optional "Countdown style" in the event editor: card colour (Auto = subject folder / type colour, or one of the
 * 12 palette colours) and how the big number counts (Auto / Days / Weeks).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountdownStyleSection(color: Int, unit: Int, autoColor: Color, onColor: (Int) -> Unit, onUnit: (Int) -> Unit) {
    Column {
        Text(stringResource(R.string.cd_style), style = MaterialTheme.typography.titleMedium, color = D.c.ink, modifier = Modifier.padding(bottom = 4.dp))
        Text(stringResource(R.string.cd_style_desc), style = MaterialTheme.typography.bodySmall, color = D.c.muted, modifier = Modifier.padding(bottom = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(autoColor, color < 0, stringResource(R.string.cd_color_auto), auto = true) { onColor(-1) }
            FolderPalette.forEachIndexed { i, c -> Swatch(c, color == i, stringResource(R.string.cd_color_n, i + 1)) { onColor(i) } }
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                CountdownStyles.UNIT_AUTO to R.string.cd_unit_auto,
                CountdownStyles.UNIT_DAYS to R.string.cd_unit_days_only,
                CountdownStyles.UNIT_WEEKS to R.string.cd_unit_weeks,
            ).forEach { (u, res) -> Chip(stringResource(res), unit == u, { onUnit(u) }) }
        }
    }
}

@Composable
private fun Swatch(c: Color, selected: Boolean, label: String, auto: Boolean = false, onClick: () -> Unit) {
    val fg = contentOn(c)
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .border(2.dp, if (selected) D.c.ink else Color.Transparent, CircleShape)
            .padding(4.dp).clip(CircleShape).background(c)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size(18.dp))
        else if (auto) Icon(Icons.Rounded.AutoMode, null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

/** Editor top-bar action: open this event's live countdown. */
@Composable
fun ShowCountdownButton(e: PlanEvent) {
    IconButton(onClick = { pane.push(Screen.Countdown(e.id, 0L)) }) {
        Icon(Icons.Rounded.HourglassTop, stringResource(R.string.cd_show_countdown), tint = D.c.muted)
    }
}
