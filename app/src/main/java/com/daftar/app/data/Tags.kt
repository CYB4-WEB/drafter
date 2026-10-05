package com.daftar.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * A tag / colour label. [color] indexes [Tags.PALETTE] (8 label colours). Built-in tags have a [key] and, until the user
 * renames them, a blank [name] — the UI then shows the localized default (English / Arabic follow the app language).
 */
@Serializable
data class Tag(val id: String, val name: String = "", val color: Int = 0, val key: String? = null)

/**
 * A saved filter. Two groups of rules:
 * - tag rule: the item carries any (or, with [tagsAll], every) tag of [tags];
 * - other rules (all must hold): type in [kinds] (Kind names), inside [folder] (path relative to the library root),
 *   modified [date] ([Tags.DATE_TODAY] / [Tags.DATE_WEEK] / [Tags.DATE_OLDER]; files only), [untagged].
 * The groups are joined with AND, or with OR when [anyGroup] ("tag Exam week OR modified this week in subject X").
 * A group without rules is left out; a filter without any rule matches everything.
 */
@Serializable
data class SmartFilter(
    val id: String,
    val name: String = "",
    val key: String? = null,
    val tags: List<String> = emptyList(),
    val tagsAll: Boolean = false,
    val kinds: List<String> = emptyList(),
    val folder: String? = null,
    val date: Int = 0,
    val untagged: Boolean = false,
    val anyGroup: Boolean = false,
) {
    val hasTagRule get() = tags.isNotEmpty()
    val hasOtherRule get() = kinds.isNotEmpty() || folder != null || date != 0 || untagged
}

/** Tags of a binned item: [orig] = its library-relative path, [entries] = path below it ("" = the item) → tag ids. */
@Serializable
data class TrashedTags(val orig: String, val entries: Map<String, List<String>>)

@Serializable
private data class TagsData(
    val tags: List<Tag> = emptyList(),
    /** Library-relative path → tag ids. */
    val assign: Map<String, List<String>> = emptyMap(),
    val filters: List<SmartFilter> = emptyList(),
    /** Recycle-bin entry id → tags it had (restored with it). */
    val trashed: Map<String, TrashedTags> = emptyMap(),
)

/**
 * Tags, colour labels and smart filters (tags-agent). Stored in `filesDir/tags.json`, keyed by the path relative to the
 * library root so the file survives a moved app-data directory. [Storage] keeps it in sync on rename / move / duplicate /
 * delete / restore. State is copy-on-write (readable from any thread); [version] is bumped on the main thread so Compose
 * screens re-read. Saving is asynchronous (one writer thread, temp file + rename).
 */
object Tags {
    /** Label colours (ARGB): red, orange, yellow, green, teal, blue, purple, grey. */
    val PALETTE = intArrayOf(
        0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFFEAB308.toInt(), 0xFF22C55E.toInt(),
        0xFF14B8A6.toInt(), 0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF64748B.toInt(),
    )

    const val DATE_ANY = 0
    const val DATE_TODAY = 1
    /** Last 7 days (rolling). */
    const val DATE_WEEK = 2
    /** Older than 7 days. */
    const val DATE_OLDER = 3

    // built-in tag keys (names come from strings_tags.xml)
    const val K_REVIEW = "review"
    const val K_EXAM = "exam"
    const val K_IMPORTANT = "important"
    const val K_DONE = "done"
    const val K_HOMEWORK = "homework"

    // built-in filter keys
    const val F_REVIEW = "review"
    const val F_EXAM = "exam"
    const val F_RECENT = "recent"
    const val F_UNTAGGED = "untagged_notes"

    private const val DAY = 24 * 3600_000L

    private val lock = Any()
    @Volatile private var data = TagsData()
    private var file: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tags-io").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var savePending = false

    /** Bumped on every change (read it in composables that show tags). */
    var version by mutableIntStateOf(0)
        private set

    val tags: List<Tag> get() = data.tags
    val filters: List<SmartFilter> get() = data.filters

    // ============================================================================ setup & persistence

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "tags.json")
        file = f
        val loaded = runCatching { json.decodeFromString<TagsData>(f.readText()) }.getOrNull()
        data = loaded ?: defaults().also { if (!f.exists()) saveSoon() }
        // drop tags of paths that vanished outside the app and bin records that were purged — off the main thread
        io.execute { runCatching { prune() } }
    }

    private fun defaults(): TagsData {
        val t = listOf(
            Tag("t_review", color = 1, key = K_REVIEW),
            Tag("t_exam", color = 0, key = K_EXAM),
            Tag("t_important", color = 6, key = K_IMPORTANT),
            Tag("t_done", color = 3, key = K_DONE),
            Tag("t_homework", color = 5, key = K_HOMEWORK),
        )
        return TagsData(tags = t, filters = builtinFilters())
    }

    private fun builtinFilters() = listOf(
        SmartFilter("f_review", key = F_REVIEW, tags = listOf("t_review")),
        SmartFilter("f_exam", key = F_EXAM, tags = listOf("t_exam")),
        SmartFilter("f_recent", key = F_RECENT, date = DATE_WEEK),
        SmartFilter("f_untagged", key = F_UNTAGGED, kinds = listOf(Kind.NOTE.name), untagged = true),
    )

    private fun bump() {
        if (Looper.myLooper() == Looper.getMainLooper()) version++ else main.post { version++ }
    }

    /** Applies [change] to the state, bumps [version] and schedules a save. No-op when nothing changed. */
    private inline fun edit(change: (TagsData) -> TagsData) {
        synchronized(lock) {
            val n = change(data)
            if (n == data) return
            data = n
        }
        bump(); saveSoon()
    }

    private fun saveSoon() {
        if (savePending) return
        savePending = true
        io.execute {
            savePending = false
            val f = file ?: return@execute
            runCatching {
                val tmp = File(f.parentFile, "tags.json.tmp")
                tmp.writeText(json.encodeToString(data))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    private fun prune() {
        if (!Storage.isReady()) return
        val binIds = runCatching { Trash.items().map { it.id }.toSet() }.getOrNull() ?: return
        edit { d ->
            val assign = d.assign.filter { (k, v) -> v.isNotEmpty() && fileOf(k).exists() }
            val trashed = d.trashed.filterKeys { it in binIds }
            if (assign.size == d.assign.size && trashed.size == d.trashed.size) d else d.copy(assign = assign, trashed = trashed)
        }
    }

    // ============================================================================ paths

    /** Library-relative key of [f] ("" for the root itself), or null when [f] is outside the library. */
    fun keyOf(f: File): String? {
        if (!Storage.isReady()) return null
        val r = Storage.root.absolutePath
        val a = f.absolutePath
        return when {
            a == r -> ""
            a.startsWith("$r/") -> a.substring(r.length + 1)
            else -> null
        }
    }

    fun fileOf(key: String): File = if (key.isEmpty()) Storage.root else File(Storage.root, key)

    private fun inside(key: String, base: String) = key == base || key.startsWith("$base/")

    // ============================================================================ reading

    fun tag(id: String): Tag? = data.tags.firstOrNull { it.id == id }

    fun filter(id: String): SmartFilter? = data.filters.firstOrNull { it.id == id }

    fun idsOf(f: File): List<String> {
        val k = keyOf(f) ?: return emptyList()
        return data.assign[k] ?: emptyList()
    }

    /** Tags of [f] in tag-list order. */
    fun tagsOf(f: File): List<Tag> {
        val ids = idsOf(f)
        if (ids.isEmpty()) return emptyList()
        return data.tags.filter { it.id in ids }
    }

    /** How many items carry each tag (id → count). */
    fun usage(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (ids in data.assign.values) for (id in ids) m[id] = (m[id] ?: 0) + 1
        return m
    }

    // ============================================================================ tag editing

    private fun newId(prefix: String) = prefix + System.currentTimeMillis().toString(36) + (Math.random() * 1e5).toInt().toString(36)

    fun createTag(name: String, color: Int): Tag {
        val t = Tag(newId("t_"), name.trim(), color.coerceIn(0, PALETTE.lastIndex))
        edit { it.copy(tags = it.tags + t) }
        return t
    }

    /** Renames / recolours [t] (matched by id). A built-in keeps its key; a blank name brings back the localized default. */
    fun updateTag(t: Tag) = edit { d -> d.copy(tags = d.tags.map { if (it.id == t.id) t.copy(name = t.name.trim()) else it }) }

    /** Deletes a tag everywhere (items, filters, bin records). */
    fun deleteTag(id: String) = edit { d ->
        d.copy(
            tags = d.tags.filter { it.id != id },
            assign = d.assign.mapValues { (_, v) -> v - id }.filterValues { it.isNotEmpty() },
            filters = d.filters.map { f -> if (id in f.tags) f.copy(tags = f.tags - id) else f },
            trashed = d.trashed.mapValues { (_, t) -> t.copy(entries = t.entries.mapValues { (_, v) -> v - id }.filterValues { it.isNotEmpty() }) },
        )
    }

    /** Replaces the tags of [f]. */
    fun setTags(f: File, ids: List<String>) {
        val k = keyOf(f) ?: return
        edit { d ->
            val clean = ids.distinct().filter { id -> d.tags.any { it.id == id } }
            d.copy(assign = if (clean.isEmpty()) d.assign - k else d.assign + (k to clean))
        }
    }

    /** Adds ([on]) or removes tag [id] on every file of [files]. */
    fun toggle(files: List<File>, id: String, on: Boolean) {
        val keys = files.mapNotNull(::keyOf)
        if (keys.isEmpty()) return
        edit { d ->
            val m = d.assign.toMutableMap()
            for (k in keys) {
                val cur = m[k] ?: emptyList()
                val n = if (on) (if (id in cur) cur else cur + id) else cur - id
                if (n.isEmpty()) m.remove(k) else m[k] = n
            }
            d.copy(assign = m)
        }
    }

    // ============================================================================ filters

    fun newFilter(): SmartFilter = SmartFilter(newId("f_"))

    /** Adds or replaces (by id) a smart filter. */
    fun saveFilter(f: SmartFilter) = edit { d ->
        val i = d.filters.indexOfFirst { it.id == f.id }
        d.copy(filters = if (i < 0) d.filters + f else d.filters.toMutableList().also { it[i] = f })
    }

    fun deleteFilter(id: String) = edit { d -> d.copy(filters = d.filters.filter { it.id != id }) }

    /** Brings back missing built-in filters (and their built-in tags), keeping the user's own ones. */
    fun restoreBuiltins() = edit { d ->
        val def = defaults()
        val tags = d.tags + def.tags.filter { t -> d.tags.none { it.id == t.id } }
        val filters = d.filters + def.filters.filter { f -> d.filters.none { it.id == f.id } }
        d.copy(tags = tags, filters = filters)
    }

    // ============================================================================ Storage hooks

    /** [from] was renamed / moved to [to] (also every item inside a folder). */
    fun moved(from: File, to: File) {
        val a = keyOf(from) ?: return
        val b = keyOf(to)
        edit { d ->
            val m = LinkedHashMap<String, List<String>>()
            for ((k, v) in d.assign) {
                if (inside(k, a)) { if (b != null) m[b + k.substring(a.length)] = v } else m[k] = v
            }
            val filters = d.filters.map { f ->
                val fo = f.folder
                if (fo != null && b != null && inside(fo, a)) f.copy(folder = b + fo.substring(a.length)) else f
            }
            if (m == d.assign && filters == d.filters) d else d.copy(assign = m, filters = filters)
        }
    }

    /** [from] was duplicated as [to]: the copy gets the same tags. */
    fun copied(from: File, to: File) {
        val a = keyOf(from) ?: return
        val b = keyOf(to) ?: return
        edit { d ->
            val add = d.assign.filterKeys { inside(it, a) }.mapKeys { (k, _) -> b + k.substring(a.length) }
            if (add.isEmpty()) d else d.copy(assign = d.assign + add)
        }
    }

    /** [f] went to the recycle bin as entry [trashId]: its tags (and those inside a folder) wait there for a restore. */
    fun trashed(f: File, trashId: String) {
        val a = keyOf(f) ?: return
        edit { d ->
            val (gone, keep) = d.assign.entries.partition { inside(it.key, a) }
            if (gone.isEmpty()) d
            else d.copy(
                assign = keep.associate { it.key to it.value },
                trashed = d.trashed + (trashId to TrashedTags(a, gone.associate { it.key.substring(a.length).removePrefix("/") to it.value })),
            )
        }
    }

    /** [f] was deleted for good. */
    fun removed(f: File) {
        val a = keyOf(f) ?: return
        edit { d -> d.copy(assign = d.assign.filterKeys { !inside(it, a) }) }
    }

    /**
     * Something was restored from the bin ([Storage.repin] runs right after every restore). Bin records whose entry left
     * the bin are put back on the restored item: its original path, or — when that name was taken meanwhile — the
     * untagged "Name (n)" copy Trash.restore created next to it.
     */
    fun restoredFromBin() {
        if (data.trashed.isEmpty()) return
        io.execute {
            runCatching {
                val binIds = Trash.items().map { it.id }.toSet()
                val back = data.trashed.filterKeys { it !in binIds }
                if (back.isEmpty()) return@runCatching
                edit { d ->
                    val m = d.assign.toMutableMap()
                    for ((_, rec) in back) {
                        val target = restoredTarget(rec, m) ?: continue
                        for ((sub, ids) in rec.entries) {
                            val k = if (sub.isEmpty()) target else "$target/$sub"
                            val valid = ids.filter { id -> d.tags.any { it.id == id } }
                            if (valid.isNotEmpty()) m[k] = valid
                        }
                    }
                    d.copy(assign = m, trashed = d.trashed - back.keys)
                }
            }
        }
    }

    private fun restoredTarget(rec: TrashedTags, assign: Map<String, List<String>>): String? {
        val orig = rec.orig
        val f = fileOf(orig)
        if (f.exists() && assign[orig].isNullOrEmpty()) return orig
        val parent = f.parentFile ?: return null
        val isDir = rec.entries.keys.any { it.isNotEmpty() } || f.isDirectory || !f.name.contains('.')
        val base = if (isDir) f.name else f.nameWithoutExtension
        val ext = if (isDir) "" else f.extension
        val dot = if (ext.isEmpty()) "" else ".$ext"
        for (n in 40 downTo 2) {
            val c = File(parent, "$base ($n)$dot")
            val k = keyOf(c) ?: continue
            if (c.exists() && assign[k].isNullOrEmpty()) return k
        }
        return null
    }

    // ============================================================================ matching

    private fun startOfToday(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Precomputed clock for one evaluation pass. */
    class Clock(now: Long = System.currentTimeMillis()) {
        val today = startOfToday(now)
        val week = now - 7 * DAY
    }

    /** Whether [e] (library-relative [key]) matches [f]. */
    fun matches(f: SmartFilter, e: Entry, key: String, clock: Clock, d: Map<String, List<String>> = data.assign): Boolean {
        val ids = d[key] ?: emptyList()
        val tagOk = if (!f.hasTagRule) null else if (f.tagsAll) f.tags.all { it in ids } else f.tags.any { it in ids }
        val otherOk = if (!f.hasOtherRule) null else {
            (f.kinds.isEmpty() || e.kind.name in f.kinds) &&
                (f.folder == null || (key != f.folder && (f.folder.isEmpty() || key.startsWith(f.folder + "/")))) &&
                (!f.untagged || ids.isEmpty()) &&
                (f.date == DATE_ANY || (e.kind != Kind.FOLDER && e.file.lastModified().let { t ->
                    when (f.date) { DATE_TODAY -> t >= clock.today; DATE_WEEK -> t >= clock.week; else -> t < clock.week }
                }))
        }
        return when {
            tagOk == null && otherOk == null -> true
            tagOk == null -> otherOk!!
            otherOk == null -> tagOk
            f.anyGroup -> tagOk || otherOk
            else -> tagOk && otherOk
        }
    }

    /** Visits every library item (not the root, no hidden entries) with its key. Blocking. */
    private inline fun walk(block: (Entry, String) -> Unit) {
        if (!Storage.isReady()) return
        val root = Storage.root
        val rp = root.absolutePath.length + 1
        root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }.forEach {
            if (it != root && !it.name.startsWith(".")) block(Storage.entry(it), it.absolutePath.substring(rp))
        }
    }

    /** Items matching [f], newest first. Blocking — call off the main thread. */
    fun evaluate(f: SmartFilter, limit: Int = 500): List<Entry> {
        val clock = Clock()
        val d = data.assign
        val out = ArrayList<Entry>()
        // a pure tag filter only needs the tagged paths
        if (f.hasTagRule && !f.hasOtherRule) {
            for (k in d.keys) {
                val file = fileOf(k)
                if (!file.exists()) continue
                val e = Storage.entry(file)
                if (matches(f, e, k, clock, d)) out.add(e)
            }
        } else walk { e, k -> if (matches(f, e, k, clock, d)) out.add(e) }
        return out.sortedByDescending { it.file.lastModified() }.take(limit)
    }

    /** Number of matches of every filter (id → count) in one library walk. Blocking. */
    fun counts(filters: List<SmartFilter> = data.filters): Map<String, Int> {
        val clock = Clock()
        val d = data.assign
        val m = HashMap<String, Int>()
        walk { e, k -> for (f in filters) if (matches(f, e, k, clock, d)) m[f.id] = (m[f.id] ?: 0) + 1 }
        return m
    }

    // ============================================================================ search

    /** Display name of [t] (built-ins resolve their localized default while unnamed). */
    fun nameOf(ctx: Context, t: Tag): String = t.name.ifBlank { t.key?.let { k -> builtinName(ctx, k) } ?: "" }

    private fun builtinName(ctx: Context, key: String): String? {
        val res = when (key) {
            K_REVIEW -> com.daftar.app.R.string.tags_default_review
            K_EXAM -> com.daftar.app.R.string.tags_default_exam
            K_IMPORTANT -> com.daftar.app.R.string.tags_default_important
            K_DONE -> com.daftar.app.R.string.tags_default_done
            K_HOMEWORK -> com.daftar.app.R.string.tags_default_homework
            else -> return null
        }
        return ctx.getString(res)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Splits a search query into plain text and `#tag` groups. `#Exam week`, `#exam_week`, `#examweek` and a prefix such as
     * `#exa` all find "Exam week". Each group is the set of tag ids one `#token` stands for (an item needs one of them);
     * groups are combined with AND. A token that matches no tag gives an empty group (no results).
     */
    fun parseQuery(ctx: Context, q: String): Pair<String, List<Set<String>>> {
        if (!q.contains('#')) return q.trim() to emptyList()
        val named = data.tags.map { it to nameOf(ctx, it) }
        val text = StringBuilder()
        val groups = ArrayList<Set<String>>()
        var i = 0
        while (i < q.length) {
            val ch = q[i]
            if (ch != '#' || (i > 0 && !q[i - 1].isWhitespace())) { text.append(ch); i++; continue }
            val rest = q.substring(i + 1)
            // longest full tag name (may contain spaces) right after '#'
            val full = named.filter { (_, n) ->
                n.isNotEmpty() && rest.length >= n.length && rest.regionMatches(0, n, 0, n.length, ignoreCase = true) &&
                    (rest.length == n.length || rest[n.length].isWhitespace())
            }.maxByOrNull { it.second.length }
            if (full != null) {
                groups.add(setOf(full.first.id)); i += 1 + full.second.length; continue
            }
            val token = rest.takeWhile { !it.isWhitespace() }
            if (token.isEmpty()) { i++; continue }
            val nt = norm(token)
            val exact = named.filter { norm(it.second) == nt }
            val hits = exact.ifEmpty { named.filter { nt.isNotEmpty() && norm(it.second).startsWith(nt) } }
            groups.add(hits.map { it.first.id }.toSet())
            i += 1 + token.length
        }
        return text.toString().replace(Regex("\\s+"), " ").trim() to groups
    }

    /**
     * Library search with tags: plain [text] in the name, every group of [groups] matched (see [parseQuery]), every tag in
     * [required] present, and — when [smart] is set — that filter matched. Newest first. Blocking.
     */
    fun search(text: String, groups: List<Set<String>>, required: Set<String>, smart: SmartFilter?, limit: Int = 300): List<Entry> {
        val clock = Clock()
        val d = data.assign
        val out = ArrayList<Entry>()
        fun ok(e: Entry, k: String): Boolean {
            val ids = d[k] ?: emptyList()
            if (text.isNotEmpty() && !e.file.name.contains(text, ignoreCase = true)) return false
            if (required.any { it !in ids }) return false
            if (groups.any { g -> g.none { it in ids } }) return false
            return smart == null || matches(smart, e, k, clock, d)
        }
        val tagOnly = groups.isNotEmpty() || required.isNotEmpty() || (smart != null && smart.hasTagRule && !smart.hasOtherRule)
        if (tagOnly) {
            for (k in d.keys) {
                val file = fileOf(k)
                if (!file.exists()) continue
                val e = Storage.entry(file)
                if (ok(e, k)) out.add(e)
            }
            return out.sortedByDescending { it.file.lastModified() }.take(limit)
        }
        walk { e, k -> if (ok(e, k)) out.add(e) }
        return if (text.isEmpty()) out.sortedByDescending { it.file.lastModified() }.take(limit) else out.take(limit)
    }
}
