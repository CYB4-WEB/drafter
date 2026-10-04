package com.daftar.app.planner

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.daftar.app.R
import com.daftar.app.ui.toast
import com.daftar.app.ui.uriFor
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar (RFC 5545) export of planner events. Times are written in UTC ("…Z", no VTIMEZONE needed),
 * all-day events as VALUE=DATE, weekly events with RRULE:FREQ=WEEKLY[;UNTIL=…], reminders as VALARM.
 * Lines end with CRLF and are folded at 75 octets without splitting UTF-8 sequences.
 */
object Ics {
    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val ALL_DAY_BASE_MIN = 9 * 60

    /** Builds the whole VCALENDAR text. [ctx] localizes category names. */
    fun build(ctx: Context, events: List<PlanEvent>, now: Long = System.currentTimeMillis()): String {
        val c = localized(ctx)
        val out = StringBuilder()
        fun line(s: String) = fold(s, out)
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Daftar//Planner 2.0//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:" + text(c.getString(R.string.planner_ics_calname)))
        val stamp = UTC.format(Instant.ofEpochMilli(now))
        for (e in events.sortedBy { it.start }) {
            line("BEGIN:VEVENT")
            line("UID:daftar-${e.id}@com.daftar.app")
            line("DTSTAMP:$stamp")
            if (e.allDay) {
                val (d0, d1) = DeviceCalendar.allDayDates(e)
                line("DTSTART;VALUE=DATE:" + d0.format(DATE))
                line("DTEND;VALUE=DATE:" + d1.format(DATE))
            } else {
                line("DTSTART:" + UTC.format(Instant.ofEpochMilli(e.start)))
                // DTEND must be later than DTSTART; a zero-length item (assignment due time) simply has no DTEND.
                if (e.end > e.start) line("DTEND:" + UTC.format(Instant.ofEpochMilli(e.end)))
            }
            if (e.weekly) {
                val until = when {
                    e.until <= 0 -> ""
                    e.allDay -> ";UNTIL=" + Planner.dateOf(e.until).format(DATE)
                    else -> ";UNTIL=" + UTC.format(Instant.ofEpochMilli(e.until))
                }
                line("RRULE:FREQ=WEEKLY$until")
            }
            line("SUMMARY:" + text(e.title))
            if (e.description.isNotBlank()) line("DESCRIPTION:" + text(e.description.trim()))
            if (e.location.isNotBlank()) line("LOCATION:" + text(e.location))
            if (e.link.isNotBlank()) line("URL:" + e.link.replace("\r", "").replace("\n", ""))
            line("CATEGORIES:" + text(c.getString(typeLabelRes(e.type))))
            line("TRANSP:OPAQUE")
            for (m in e.reminders.distinct().sorted()) {
                // Daftar reminds all-day events relative to 09:00; triggers are relative to DTSTART (midnight).
                val rel = if (e.allDay) m - ALL_DAY_BASE_MIN else m
                line("BEGIN:VALARM")
                line("ACTION:DISPLAY")
                line("DESCRIPTION:" + text(e.title))
                line("TRIGGER:" + if (rel >= 0) "-PT${rel}M" else "PT${-rel}M")
                line("END:VALARM")
            }
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    /** TEXT value escaping (RFC 5545 §3.3.11). */
    internal fun text(s: String): String = buildString(s.length + 8) {
        for (ch in s.replace("\r\n", "\n").replace('\r', '\n')) when (ch) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            else -> if (ch.code < 0x20 && ch != '\t') Unit else append(ch)
        }
    }

    /** Appends [s] folded to ≤ 75 octets per physical line (continuations start with one space), CRLF-terminated. */
    internal fun fold(s: String, out: StringBuilder) {
        var octets = 0
        var limit = 75
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val bytes = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (octets + bytes > limit) {
                out.append("\r\n ")
                octets = 0
                limit = 74 // the leading space counts
            }
            out.appendCodePoint(cp)
            octets += bytes
            i += n
        }
        out.append("\r\n")
    }

    /** Writes the export to the cache and returns the file. Runs on any thread. */
    fun write(ctx: Context, events: List<PlanEvent>): File {
        val dir = File(ctx.cacheDir, "ics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export
        val f = File(dir, "Daftar-planner-${LocalDate.now()}.ics")
        f.writeText(build(ctx, events), Charsets.UTF_8)
        return f
    }

    /** Opens the share sheet for an exported .ics (calendar apps, email, Drive…). Main thread. */
    fun share(ctx: Context, f: File) {
        val uri = uriFor(ctx, f)
        val send = Intent(Intent.ACTION_SEND).setType("text/calendar")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(f.name, uri)
        try {
            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.planner_export_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(ctx, ctx.getString(R.string.no_app_found))
        }
    }
}
