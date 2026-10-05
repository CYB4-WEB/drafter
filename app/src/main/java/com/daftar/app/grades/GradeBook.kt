package com.daftar.app.grades

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.json
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors

/**
 * Grades store: `filesDir/grades.json`, held in memory as Compose state.
 * Mutations run on the main thread; writes go tmp + rename on one background thread (ordered, atomic).
 */
object GradeBook {
    /** Bumped on every change (Compose observable). */
    var version by mutableIntStateOf(0)
        private set

    var data by mutableStateOf(GradesData())
        private set

    /** Planner exam handed over by "Grade it" (consumed once by GradesScreen). */
    var pendingExam by mutableStateOf<Long?>(null)

    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "grades-io").apply { isDaemon = true } }

    val scale: GradeScale get() = Scales.active(data)

    /** Loads once (small file; read synchronously so the first frame already has the numbers). */
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "grades.json")
        file = f
        runCatching { if (f.exists()) data = json.decodeFromString<GradesData>(f.readText()) }
        version++
    }

    private fun commit(d: GradesData) {
        data = d
        version++
        val f = file ?: return
        val text = json.encodeToString(d)
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, "grades.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    fun newId(): Long {
        var id = System.currentTimeMillis()
        val used = HashSet<Long>()
        data.terms.forEach { t -> used.add(t.id); t.courses.forEach { c -> used.add(c.id); c.assessments.forEach { used.add(it.id) } } }
        while (id in used) id++
        return id
    }

    // ---------- terms ----------
    fun upsertTerm(t: Term) {
        val l = data.terms.toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l.add(t)
        commit(data.copy(terms = l))
    }

    fun deleteTerm(id: Long) = commit(data.copy(terms = data.terms.filterNot { it.id == id }))

    fun restoreTerm(t: Term, index: Int) {
        val l = data.terms.toMutableList()
        l.add(index.coerceIn(0, l.size), t)
        commit(data.copy(terms = l))
    }

    fun moveTerm(id: Long, delta: Int) {
        val l = data.terms.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        commit(data.copy(terms = l))
    }

    // ---------- courses ----------
    fun termOf(courseId: Long): Term? = data.terms.firstOrNull { t -> t.courses.any { it.id == courseId } }
    fun course(id: Long): Course? = data.terms.firstNotNullOfOrNull { t -> t.courses.firstOrNull { it.id == id } }

    /** Inserts or replaces [c] inside term [termId] (moves it there if it lived in another term). */
    fun upsertCourse(termId: Long, c: Course) {
        val terms = data.terms.map { t ->
            val without = t.courses.filterNot { it.id == c.id }
            if (t.id == termId) {
                val i = t.courses.indexOfFirst { it.id == c.id }
                t.copy(courses = if (i >= 0) t.courses.toMutableList().also { it[i] = c } else without + c)
            } else t.copy(courses = without)
        }
        commit(data.copy(terms = terms))
    }

    fun deleteCourse(id: Long) = commit(data.copy(terms = data.terms.map { t -> t.copy(courses = t.courses.filterNot { it.id == id }) }))

    fun updateCourse(id: Long, f: (Course) -> Course) {
        commit(data.copy(terms = data.terms.map { t -> t.copy(courses = t.courses.map { if (it.id == id) f(it) else it }) }))
    }

    // ---------- assessments ----------
    fun upsertAssessment(courseId: Long, a: Assessment) = updateCourse(courseId) { c ->
        val i = c.assessments.indexOfFirst { it.id == a.id }
        c.copy(assessments = if (i >= 0) c.assessments.toMutableList().also { it[i] = a } else c.assessments + a)
    }

    fun deleteAssessment(courseId: Long, id: Long) = updateCourse(courseId) { c -> c.copy(assessments = c.assessments.filterNot { it.id == id }) }

    fun setAssessments(courseId: Long, list: List<Assessment>) = updateCourse(courseId) { it.copy(assessments = list) }

    // ---------- scale ----------
    fun setScale(id: String, aPlus433: Boolean = data.usAPlus433, custom: GradeScale? = data.custom) =
        commit(data.copy(scaleId = id, usAPlus433 = aPlus433, custom = custom))

    /** Assessment graded from planner exam [eventId], if any (course id to assessment). */
    fun gradedFrom(eventId: Long): Pair<Course, Assessment>? {
        for (t in data.terms) for (c in t.courses) c.assessments.firstOrNull { it.eventId == eventId }?.let { return c to it }
        return null
    }
}
