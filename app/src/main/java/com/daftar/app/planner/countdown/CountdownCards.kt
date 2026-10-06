package com.daftar.app.planner.countdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.Planner
import com.daftar.app.planner.occurrenceSummary
import com.daftar.app.planner.typeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** eventId → linked subject folder, resolved off the main thread; follows planner and library changes. */
@Composable
fun rememberSubjectMap(): Map<Long, SubjectInfo> {
    val pv = Planner.version
    val sv = Storage.version
    val map by produceState(emptyMap<Long, SubjectInfo>(), pv, sv) {
        val events = Planner.all()
        value = withContext(Dispatchers.IO) { Subjects.map(events) }
    }
    return map
}

/** Type icon inside a thin ring (the reference's "cap in a circle"), flat. */
@Composable
fun IconRing(icon: ImageVector, tint: Color, size: Dp) {
    Box(Modifier.size(size).border(if (size >= 56.dp) 2.dp else 1.5.dp, tint.copy(alpha = 0.9f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/**
 * Countdown card in the event colour: icon ring, title, subtitle, full date, and the darker end block
 * ("1 / day to go"). The block sits at the end side, so it moves to the left in Arabic.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CountdownCard(
    o: Occurrence,
    subject: SubjectInfo?,
    now: Long,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val styles = CountdownStyles.version
    val e = o.event
    val style = CountdownStyles.get(ctx, e.id)
    val bg = cardColor(e, subject, style)
    val fg = contentOn(bg)
    val soft = fg.copy(alpha = 0.86f)
    val label = endLabel(ctx, o, now, style.unit)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = if (compact) 64.dp else 104.dp)
                .padding(start = if (compact) 12.dp else 16.dp, end = 12.dp, top = if (compact) 10.dp else 14.dp, bottom = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconRing(typeIcon(e.type), fg, if (compact) 36.dp else 48.dp)
            Spacer(Modifier.width(if (compact) 12.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.title, color = fg, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
                )
                if (!compact) Text(subtitleOf(ctx, e, subject), style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (compact) 0.dp else 2.dp)) {
                    if (e.weekly) {
                        Icon(Icons.Rounded.Repeat, stringResource(R.string.cd_weekly), tint = soft, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (compact) occurrenceSummary(ctx, o) else fullDate(ctx, o),
                        style = MaterialTheme.typography.bodySmall, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        EndBlock(label, fg, blockOn(bg), compact)
    }
}

@Composable
private fun EndBlock(label: EndLabel, fg: Color, block: Color, compact: Boolean) {
    Column(
        Modifier.fillMaxHeight().widthIn(min = if (compact) 76.dp else 108.dp, max = if (compact) 116.dp else 150.dp)
            .background(block).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        when {
            label.state == CdState.DONE -> {
                Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size(if (compact) 24.dp else 34.dp))
                Text(label.unit, style = MaterialTheme.typography.labelMedium, color = fg, textAlign = TextAlign.Center, maxLines = 1)
            }
            label.number != null -> {
                Text(
                    label.number, color = fg, fontWeight = FontWeight.Bold, maxLines = 1,
                    style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
                )
                Text(label.unit, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.9f), textAlign = TextAlign.Center, maxLines = 2)
            }
            else -> Text(
                label.unit, color = fg, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            )
        }
    }
}
