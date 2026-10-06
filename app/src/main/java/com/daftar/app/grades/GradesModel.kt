package com.daftar.app.grades

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** One row of a grading scale: a course percentage ≥ [minPct] earns [letter] worth [points]. */
@Serializable
data class GradeBand(val letter: String, val minPct: Double, val points: Double)

/** A grading scale; [bands] are kept sorted by minPct, highest first. [max] = the scale's top GPA (4.0, 5.0 …). */
@Serializable
data class GradeScale(val id: String, val bands: List<GradeBand>, val max: Double) {
    fun sorted() = copy(bands = bands.sortedByDescending { it.minPct })

    /** Letter for a percentage (null when the scale is empty). */
    fun bandFor(pct: Double): GradeBand? = bands.sortedByDescending { it.minPct }.firstOrNull { pct + 1e-9 >= it.minPct }
        ?: bands.minByOrNull { it.minPct }

    fun band(letter: String): GradeBand? = bands.firstOrNull { sameLetter(it.letter, letter) }
}

/** A graded component of a course (Midterm 30 %, Final 40 % …). [score] null = not graded yet. */
@Serializable
data class Assessment(
    val id: Long,
    val name: String,
    val weight: Double,
    val score: Double? = null,
    val outOf: Double = 100.0,
    /** Planner exam this component was graded from (0 = none). */
    val eventId: Long = 0,
) {
    val pct: Double? get() = score?.let { if (outOf > 0) it / outOf * 100.0 else null }
}

@Serializable
data class Course(
    val id: Long,
    val name: String,
    val code: String = "",
    val credits: Double = 3.0,
    /** Linked subject folder (absolute path of a first-level library folder), "" = none. */
    val subject: String = "",
    /** true = the user enters the final letter; false = computed from assessments. */
    val letterMode: Boolean = false,
    val letter: String = "",
    /** Pass/fail courses never count towards the GPA. */
    val passFail: Boolean = false,
    val passed: Boolean = true,
    val assessments: List<Assessment> = emptyList(),
)

@Serializable
data class Term(val id: Long, val name: String, val courses: List<Course> = emptyList())

@Serializable
data class GradesData(
    val terms: List<Term> = emptyList(),
    /** Active scale preset: us40, sa50, sa40 or custom. */
    val scaleId: String = Scales.US40,
    /** US 4.0 only: A+ worth 4.33 instead of 4.0. */
    val usAPlus433: Boolean = false,
    val custom: GradeScale? = null,
)

/** Letters compare case-insensitively and with "−" (U+2212) and "-" treated alike. */
fun sameLetter(a: String, b: String) = normLetter(a).equals(normLetter(b), ignoreCase = true)
fun normLetter(s: String) = s.trim().replace('−', '-').replace('–', '-')
/** Display form: proper minus sign. */
fun showLetter(s: String) = normLetter(s).replace('-', '−')

object Scales {
    const val US40 = "us40"
    const val SA50 = "sa50"
    const val SA40 = "sa40"
    const val CUSTOM = "custom"
    val presetIds = listOf(US40, SA50, SA40)

    fun us40(aPlus433: Boolean) = GradeScale(
        US40, listOf(
            GradeBand("A+", 97.0, if (aPlus433) 4.33 else 4.0), GradeBand("A", 93.0, 4.0), GradeBand("A-", 90.0, 3.7),
            GradeBand("B+", 87.0, 3.3), GradeBand("B", 83.0, 3.0), GradeBand("B-", 80.0, 2.7),
            GradeBand("C+", 77.0, 2.3), GradeBand("C", 73.0, 2.0), GradeBand("C-", 70.0, 1.7),
            GradeBand("D+", 67.0, 1.3), GradeBand("D", 60.0, 1.0), GradeBand("F", 0.0, 0.0),
        ), if (aPlus433) 4.33 else 4.0,
    )

    /** Saudi / Gulf 5-point scale. F is worth 1.0 on this scale (as published by Saudi universities). */
    val sa50 = GradeScale(
        SA50, listOf(
            GradeBand("A+", 95.0, 5.0), GradeBand("A", 90.0, 4.75), GradeBand("B+", 85.0, 4.5), GradeBand("B", 80.0, 4.0),
            GradeBand("C+", 75.0, 3.5), GradeBand("C", 70.0, 3.0), GradeBand("D+", 65.0, 2.5), GradeBand("D", 60.0, 2.0),
            GradeBand("F", 0.0, 1.0),
        ), 5.0,
    )

    val sa40 = GradeScale(
        SA40, listOf(
            GradeBand("A+", 95.0, 4.0), GradeBand("A", 90.0, 3.75), GradeBand("B+", 85.0, 3.5), GradeBand("B", 80.0, 3.0),
            GradeBand("C+", 75.0, 2.5), GradeBand("C", 70.0, 2.0), GradeBand("D+", 65.0, 1.5), GradeBand("D", 60.0, 1.0),
            GradeBand("F", 0.0, 0.0),
        ), 4.0,
    )

    fun preset(id: String, aPlus433: Boolean): GradeScale = when (id) {
        SA50 -> sa50
        SA40 -> sa40
        else -> us40(aPlus433)
    }

    fun active(d: GradesData): GradeScale =
        if (d.scaleId == CUSTOM && d.custom != null && d.custom.bands.isNotEmpty()) d.custom.sorted() else preset(d.scaleId, d.usAPlus433)
}

/** What a course currently stands at. */
data class Standing(
    /** Weighted % over the graded components (null = nothing graded). */
    val pct: Double?,
    val gradedWeight: Double,
    val totalWeight: Double,
    /** Points earned so far as a share of the whole course (0–100 of totalWeight-normalised). */
    val earned: Double,
) {
    val complete get() = totalWeight > 0 && gradedWeight >= totalWeight - 1e-6
    val remainingWeight get() = (totalWeight - gradedWeight).coerceAtLeast(0.0)
}

fun standing(c: Course): Standing {
    var gw = 0.0; var tw = 0.0; var acc = 0.0
    for (a in c.assessments) {
        if (a.weight <= 0) continue
        tw += a.weight
        val p = a.pct ?: continue
        gw += a.weight; acc += a.weight * p
    }
    return Standing(if (gw > 0) acc / gw else null, gw, tw, acc / 100.0)
}

/** The course's letter: entered letter, or computed from the assessments (projected when incomplete). */
fun courseBand(c: Course, s: GradeScale): GradeBand? {
    if (c.passFail) return null
    if (c.letterMode) return if (c.letter.isBlank()) null else s.band(c.letter) ?: GradeBand(normLetter(c.letter), 0.0, 0.0)
    val p = standing(c).pct ?: return null
    return s.bandFor(p)
}

/** True when [c] contributes to the GPA under [s]. */
fun countsForGpa(c: Course, s: GradeScale): Boolean =
    !c.passFail && c.credits > 0 && (if (c.letterMode) c.letter.isNotBlank() && s.band(c.letter) != null else standing(c).pct != null)

data class Gpa(val value: Double?, val credits: Double, val courses: Int)

fun gpaOf(courses: List<Course>, s: GradeScale): Gpa {
    var pts = 0.0; var cr = 0.0; var n = 0
    for (c in courses) {
        if (!countsForGpa(c, s)) continue
        val b = courseBand(c, s) ?: continue
        pts += b.points * c.credits; cr += c.credits; n++
    }
    return Gpa(if (cr > 0) pts / cr else null, cr, n)
}

fun termGpa(t: Term, s: GradeScale) = gpaOf(t.courses, s)
fun cumulativeGpa(d: GradesData, s: GradeScale = Scales.active(d)) = gpaOf(d.terms.flatMap { it.courses }, s)

/** Result of the needed-score calculator for one target letter. */
sealed class Need {
    /** Already guaranteed even with 0 on what is left. */
    data object Secured : Need()
    /** More than 100 % would be needed. */
    data class Unreachable(val pct: Double) : Need()
    data class Score(val pct: Double) : Need()
    /** Nothing left to grade (or no weights): the result is final. */
    data object Final : Need()
}

/**
 * Average % needed on the remaining (ungraded) components so the course ends at ≥ [targetMin] %.
 * Weights are normalised by their sum, so "Midterm 30 / Final 40 / Labs 20 / Quiz 10" and weights that do not add up to 100 both work.
 */
fun needed(c: Course, targetMin: Double): Need {
    val st = standing(c)
    if (st.totalWeight <= 0 || st.remainingWeight <= 1e-9) return Need.Final
    val x = (targetMin * st.totalWeight / 100.0 - st.earned) / st.remainingWeight * 100.0
    return when {
        x <= 0 -> Need.Secured
        x > 100.0 + 1e-9 -> Need.Unreachable(x)
        else -> Need.Score(x)
    }
}

/** "3.62" — two decimals, trimmed sensibly for whole numbers ("4.00" stays, it's a GPA). */
fun fmtGpa(v: Double?): String = if (v == null) "—" else String.format(java.util.Locale.US, "%.2f", v)
fun fmtPct(v: Double): String = if (abs(v - v.roundToInt()) < 0.05) "${v.roundToInt()}%" else String.format(java.util.Locale.US, "%.1f%%", v)
fun fmtNum(v: Double): String = if (abs(v - v.roundToInt()) < 1e-9) "${v.roundToInt()}" else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** Parses a user-typed number ("3", "3.5", "3,5", Arabic-Indic digits). */
fun parseNum(s: String): Double? {
    val t = buildString {
        for (ch in s.trim()) append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                '٫', ',' -> '.'
                else -> ch
            }
        )
    }
    return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}
