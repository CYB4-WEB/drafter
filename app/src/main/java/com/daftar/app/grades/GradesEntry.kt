package com.daftar.app.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Grade
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.planner.EventType
import com.daftar.app.planner.PlanEvent
import com.daftar.app.ui.Screen
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D

/** Study hub entry: "Grades & GPA" with the cumulative GPA. */
@Composable
fun GradesEntryCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { GradeBook.init(ctx); true }
    val c = D.c
    @Suppress("UNUSED_VARIABLE") val v = GradeBook.version
    val scale = GradeBook.scale
    val cum = cumulativeGpa(GradeBook.data, scale)
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp).card(c).clip(RoundedCornerShape(16.dp)).clickable { pane.push(Screen.Grades) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.School, null, tint = c.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.grades_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(
                if (cum.value == null) stringResource(R.string.grades_entry_empty)
                else stringResource(R.string.grades_entry_line, fmtGpa(cum.value), fmtGpa(scale.max), fmtNum(cum.credits)),
                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2,
            )
        }
        if (cum.value != null) {
            Text(fmtGpa(cum.value), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}

/** Tiny Home stat: "Cumulative GPA 3.62" — renders nothing until there is a GPA. */
@Composable
fun GpaHomeStat() {
    val ctx = LocalContext.current
    remember { GradeBook.init(ctx); true }
    val c = D.c
    @Suppress("UNUSED_VARIABLE") val v = GradeBook.version
    val scale = GradeBook.scale
    val gpa = cumulativeGpa(GradeBook.data, scale).value ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).card(c).clip(RoundedCornerShape(16.dp)).clickable { pane.push(Screen.Grades) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Grade, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.grades_cumulative), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        Text(fmtGpa(gpa), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.ink)
        Text(" / " + fmtGpa(scale.max), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

/** Planner editor action for an exam whose date has passed: hands the exam to GradesScreen's "Grade exam" dialog. */
@Composable
fun GradeItButton(e: PlanEvent) {
    if (e.type != EventType.EXAM || e.weekly || e.end > System.currentTimeMillis()) return
    val ctx = LocalContext.current
    TextButton(onClick = {
        GradeBook.init(ctx)
        GradeBook.pendingExam = e.id
        pane.push(Screen.Grades)
    }) {
        Icon(Icons.Rounded.Grade, null, tint = D.c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.grades_grade_it), color = D.c.accent)
    }
}
