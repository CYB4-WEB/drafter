package com.daftar.app.convert

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.pdf.PdfTools
import com.daftar.app.ui.Chip
import com.daftar.app.ui.FileBadge
import com.daftar.app.ui.card
import com.daftar.app.ui.kindColor
import com.daftar.app.ui.theme.D
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private val Success = Color(0xFF10B981)

/** Small square badge with a tool icon in [color] (same look as [FileBadge]). */
@Composable
fun ToolBadge(icon: ImageVector, color: Color, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(color.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.56f)) }
}

/** Source → target badges (or the tool badge for PDF tools). */
@Composable
fun ConvBadges(conv: Conv, size: Dp = 32.dp) {
    val tool = conv.tool
    if (tool != null) {
        ToolBadge(tool, kindColor(Kind.PDF), size)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FileBadge(conv.from, size)
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = D.c.muted, modifier = Modifier.padding(horizontal = 4.dp).size(size * 0.5f))
        FileBadge(conv.to, size)
    }
}

/** Hub grid card. */
@Composable
fun ConvCard(conv: Conv, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = D.c
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) c.accent.copy(alpha = 0.08f) else c.surface)
            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).heightIn(min = 120.dp).padding(14.dp),
    ) {
        ConvBadges(conv)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(conv.title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(conv.desc), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Sheet list row: badges, title, description; optional "options" button. */
@Composable
fun ConvRow(conv: Conv, onClick: () -> Unit, onOptions: (() -> Unit)?) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBadge(conv.icon, if (conv.tool != null) kindColor(Kind.PDF) else kindColor(conv.to), 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(conv.title), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(conv.desc), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onOptions != null) {
            IconButton(onClick = onOptions) { Icon(Icons.Rounded.Tune, stringResource(R.string.convert_options), tint = c.muted) }
        } else if (conv.needsSetup) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.padding(12.dp))
        }
    }
}

/** Plain icon + label row (sheet links). */
@Composable
fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.accent) }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.accent, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = D.c.muted, modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: String, items: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = D.c.muted, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { Chip(text(it), it == selected, { onSelect(it) }) }
        }
    }
}

/** Range text valid for [pageCount] (blank = all; unknown page count = accept and let the engine check). */
fun rangeValid(range: String, pageCount: Int): Boolean =
    range.isBlank() || pageCount <= 0 || PdfTools.parseRanges(range, pageCount) != null

/** Options complete enough to run. */
fun optionsValid(conv: Conv, o: ConvOptions, pageCount: Int): Boolean {
    if (Opt.RANGE in conv.options && !rangeValid(o.range, pageCount)) return false
    if (Opt.SPLIT in conv.options) {
        return if (pageCount > 0) Engines.splitGroups(o, pageCount) != null
        else if (o.splitMode == SplitMode.EVERY) o.splitEvery >= 1 else o.splitRanges.isNotBlank()
    }
    return true
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = D.c.accent, unfocusedBorderColor = D.c.line, focusedLabelColor = D.c.accent, cursorColor = D.c.accent,
    focusedTextColor = D.c.ink, unfocusedTextColor = D.c.ink,
)

/** Every option the conversion exposes. [showAnnotations] = the source has an ink layer. */
@Composable
fun OptionsEditor(conv: Conv, o: ConvOptions, pageCount: Int, showAnnotations: Boolean, onChange: (ConvOptions) -> Unit) {
    val c = D.c
    val opts = conv.options
    if (Opt.RANGE in opts) {
        val bad = !rangeValid(o.range, pageCount)
        OutlinedTextField(
            value = o.range, onValueChange = { onChange(o.copy(range = it)) },
            label = { Text(stringResource(R.string.convert_range)) },
            placeholder = { Text(stringResource(R.string.convert_range_hint)) },
            singleLine = true, isError = bad, colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            supportingText = {
                Text(
                    when {
                        bad -> stringResource(R.string.convert_range_invalid, pageCount)
                        pageCount > 0 -> stringResource(R.string.convert_range_help, pageCount)
                        else -> stringResource(R.string.convert_range_help_plain)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
    }
    if (Opt.FORMAT2 in opts || Opt.FORMAT3 in opts) {
        val fmts = if (Opt.FORMAT3 in opts) ImgFmt.entries else listOf(ImgFmt.PNG, ImgFmt.JPG)
        ChoiceRow(stringResource(R.string.convert_format), fmts, if (o.format in fmts) o.format else fmts[0], { it.label }) { onChange(o.copy(format = it)) }
    }
    if (Opt.QUALITY in opts && (conv == Conv.COMPRESS_PDF || o.format.lossy)) {
        val q = conv.qualityChoices
        ChoiceRow(stringResource(R.string.convert_quality), q, q.minByOrNull { kotlin.math.abs(it - o.quality) } ?: q[0], { "$it%" }) {
            onChange(o.copy(quality = it))
        }
    }
    if (Opt.DPI in opts) {
        val d = conv.dpiChoices
        ChoiceRow(stringResource(R.string.convert_dpi), d, d.minByOrNull { kotlin.math.abs(it - o.dpi) } ?: d[0], { "$it" }) { onChange(o.copy(dpi = it)) }
    }
    if (Opt.SCALE in opts) {
        val s = listOf(1f, 2f, 3f)
        ChoiceRow(stringResource(R.string.convert_scale), s, s.minByOrNull { kotlin.math.abs(it - o.scale) } ?: 2f, { "${it.roundToInt()}×" }) {
            onChange(o.copy(scale = it))
        }
    }
    if (Opt.SPLIT in opts) {
        val everyLabel = stringResource(R.string.convert_split_every)
        val rangesLabel = stringResource(R.string.convert_split_ranges)
        ChoiceRow(stringResource(R.string.convert_split_mode), SplitMode.entries, o.splitMode,
            { if (it == SplitMode.EVERY) everyLabel else rangesLabel }) { onChange(o.copy(splitMode = it)) }
        val groups = if (pageCount > 0) Engines.splitGroups(o, pageCount) else null
        if (o.splitMode == SplitMode.EVERY) {
            OutlinedTextField(
                value = if (o.splitEvery > 0) o.splitEvery.toString() else "",
                onValueChange = { v ->
                    val digits = PdfTools.toAsciiDigits(v).filter { it.isDigit() }.take(5)
                    onChange(o.copy(splitEvery = digits.toIntOrNull() ?: 0))
                },
                label = { Text(stringResource(R.string.convert_split_every_label)) },
                singleLine = true, isError = o.splitEvery < 1, colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { if (groups != null) Text(stringResource(R.string.convert_split_result, groups.size)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        } else {
            val bad = o.splitRanges.isNotBlank() && pageCount > 0 && groups == null
            OutlinedTextField(
                value = o.splitRanges, onValueChange = { onChange(o.copy(splitRanges = it)) },
                label = { Text(stringResource(R.string.convert_split_ranges_label)) },
                placeholder = { Text(stringResource(R.string.convert_split_ranges_hint)) },
                singleLine = true, isError = bad, colors = fieldColors(),
                supportingText = {
                    Text(
                        when {
                            bad -> stringResource(R.string.convert_range_invalid, pageCount)
                            groups != null -> stringResource(R.string.convert_split_result, groups.size)
                            else -> stringResource(R.string.convert_split_ranges_help)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
    if (Opt.ANNOTATIONS in opts && showAnnotations) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(o.copy(annotations = !o.annotations)) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.convert_annotations), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Switch(
                checked = o.annotations, onCheckedChange = { onChange(o.copy(annotations = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
    }
}

/** Accent primary button (flat, 12dp radius). */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = D.c
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.surfaceAlt, disabledContentColor = c.muted),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = D.c
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = c.muted); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Progress (running), result (done), error (failed) or "cancelled" for one job. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobPanel(job: ConvertJob, onOpen: (File) -> Unit, onShare: (List<File>) -> Unit, onFolder: (File) -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    when (val s = job.state) {
        is ConvertJob.State.Running -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.convert_running), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                if (job.total > 0) Text(stringResource(R.string.convert_progress, job.done, job.total), style = MaterialTheme.typography.labelMedium, color = c.muted)
            }
            Spacer(Modifier.height(10.dp))
            val f = job.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = c.accent, trackColor = c.surfaceAlt, drawStopIndicator = {})
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = c.accent, trackColor = c.surfaceAlt)
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.cancel), Icons.Rounded.Close) { job.cancel() }
        }
        is ConvertJob.State.Done -> {
            val out = s.out
            Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Success, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(out.open.name, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(resultLine(ctx, out), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (out.sizeBefore >= 0 && out.sizeAfter >= 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(sizeChange(ctx, out.sizeBefore, out.sizeAfter), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
                if (out.skipped > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.convert_skipped, out.skipped), style = MaterialTheme.typography.bodySmall, color = c.danger)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.open)) { onOpen(out.open) }
                    SecondaryButton(stringResource(R.string.share), Icons.Rounded.Share) { onShare(out.files) }
                    SecondaryButton(stringResource(R.string.convert_show_in_folder), Icons.Rounded.FolderOpen) { onFolder(out.folder) }
                }
            }
        }
        is ConvertJob.State.Failed -> Row(
            Modifier.fillMaxWidth().background(c.danger.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .border(1.dp, c.danger.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger)
            Spacer(Modifier.width(12.dp))
            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
        ConvertJob.State.Cancelled -> Text(stringResource(R.string.convert_cancelled), style = MaterialTheme.typography.bodyMedium, color = c.muted)
    }
}

/** "3 files · 4.2 MB · Files › Math" */
fun resultLine(ctx: Context, out: ConvOutput): String {
    val size = out.files.sumOf { it.length() }
    val parts = ArrayList<String>()
    if (out.files.size > 1) parts.add(ctx.getString(R.string.convert_n_files, out.files.size))
    parts.add(formatSize(size))
    parts.add(displayPath(ctx, out.folder))
    return parts.joinToString(" · ")
}

fun sizeChange(ctx: Context, before: Long, after: Long): String {
    val pct = if (before > 0) ((after - before) * 100.0 / before).roundToInt() else 0
    return ctx.getString(R.string.convert_size_change, formatSize(before), formatSize(after), String.format(Locale.getDefault(), "%+d%%", pct))
}

/** One entry of "Recent conversions". */
@Composable
fun RecentRow(job: ConvertJob, onOpen: (File) -> Unit, onShare: (List<File>) -> Unit, onFolder: (File) -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val s = job.state
    val done = s as? ConvertJob.State.Done
    Row(
        Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp))
            .clickable(enabled = done != null) { done?.let { onOpen(it.out.open) } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBadge(job.conv.icon, if (job.conv.tool != null) kindColor(Kind.PDF) else kindColor(job.conv.to), 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                done?.out?.open?.name ?: job.sources.firstOrNull()?.name.orEmpty(),
                style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = when (s) {
                is ConvertJob.State.Running ->
                    if (job.total > 0) stringResource(R.string.convert_progress, job.done, job.total) else stringResource(R.string.convert_running)
                is ConvertJob.State.Failed -> s.message
                ConvertJob.State.Cancelled -> stringResource(R.string.convert_cancelled)
                is ConvertJob.State.Done -> {
                    val now = System.currentTimeMillis()
                    if (now - job.started < 60_000) stringResource(R.string.convert_just_now)
                    else DateUtils.getRelativeTimeSpanString(job.started, now, DateUtils.MINUTE_IN_MILLIS).toString()
                }
            }
            Text(
                stringResource(job.conv.title) + " · " + sub, style = MaterialTheme.typography.bodySmall,
                color = if (s is ConvertJob.State.Failed) c.danger else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (s is ConvertJob.State.Running) {
                Spacer(Modifier.height(6.dp))
                val f = job.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = c.accent, trackColor = c.surfaceAlt, drawStopIndicator = {})
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surfaceAlt)
            }
        }
        when (s) {
            is ConvertJob.State.Running -> IconButton(onClick = { job.cancel() }) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = c.muted) }
            is ConvertJob.State.Done -> {
                IconButton(onClick = { onShare(s.out.files) }) { Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = c.muted) }
                IconButton(onClick = { onFolder(s.out.folder) }) { Icon(Icons.Rounded.FolderOpen, stringResource(R.string.convert_show_in_folder), tint = c.muted) }
            }
            else -> IconButton(onClick = { ConvertJobs.remove(job) }) { Icon(Icons.Rounded.Close, stringResource(R.string.convert_remove), tint = c.muted) }
        }
    }
}
