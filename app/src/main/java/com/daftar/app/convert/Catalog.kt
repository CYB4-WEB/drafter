package com.daftar.app.convert

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Language
import androidx.compose.ui.graphics.vector.ImageVector
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.kindIcon
import java.io.File

/** Hub sections, in display order. */
enum class Group(@StringRes val title: Int) {
    PDF(R.string.convert_group_pdf),
    IMAGES(R.string.convert_group_images),
    PPTX(R.string.convert_group_pptx),
    WORD(R.string.convert_group_word),
    NOTES(R.string.convert_group_notes),
    TOOLS(R.string.convert_group_tools),
}

/** Options a conversion exposes in its setup step. */
enum class Opt { RANGE, FORMAT2, FORMAT3, QUALITY, DPI, SCALE, SPLIT, ANNOTATIONS }

enum class ImgFmt(val ext: String, val label: String) {
    PNG("png", "PNG"), JPG("jpg", "JPG"), WEBP("webp", "WEBP");

    val lossy: Boolean get() = this != PNG
}

enum class SplitMode { EVERY, RANGES }

/** Every conversion Daftar offers. [from] is the accepted source kind, [to] the produced kind (drives icon colours). */
enum class Conv(
    val group: Group,
    val from: Kind,
    val to: Kind,
    @StringRes val title: Int,
    @StringRes val desc: Int,
    val multi: Boolean = false,
    val minSources: Int = 1,
    val options: Set<Opt> = emptySet(),
    /** Tool icon (merge/split/compress); null = show source → target badges. */
    val tool: ImageVector? = null,
    /** Target file extension when [to] alone does not name it (badge label, e.g. "html"). */
    val toExt: String? = null,
) {
    PDF_IMAGES(Group.PDF, Kind.PDF, Kind.IMAGE, R.string.convert_pdf_images, R.string.convert_pdf_images_desc,
        options = setOf(Opt.RANGE, Opt.FORMAT2, Opt.QUALITY, Opt.DPI, Opt.ANNOTATIONS)),
    PDF_PPTX(Group.PDF, Kind.PDF, Kind.PPTX, R.string.convert_pdf_pptx, R.string.convert_pdf_pptx_desc,
        options = setOf(Opt.RANGE, Opt.DPI, Opt.ANNOTATIONS)),
    PDF_DOCX(Group.PDF, Kind.PDF, Kind.DOCX, R.string.convert_pdf_docx, R.string.convert_pdf_docx_desc, options = setOf(Opt.RANGE)),
    PDF_TXT(Group.PDF, Kind.PDF, Kind.TEXT, R.string.convert_pdf_txt, R.string.convert_pdf_txt_desc, options = setOf(Opt.RANGE)),

    IMAGES_PDF(Group.IMAGES, Kind.IMAGE, Kind.PDF, R.string.convert_images_pdf, R.string.convert_images_pdf_desc, multi = true),
    IMAGES_PPTX(Group.IMAGES, Kind.IMAGE, Kind.PPTX, R.string.convert_images_pptx, R.string.convert_images_pptx_desc, multi = true),
    IMAGE_FORMAT(Group.IMAGES, Kind.IMAGE, Kind.IMAGE, R.string.convert_image_format, R.string.convert_image_format_desc, multi = true,
        options = setOf(Opt.FORMAT3, Opt.QUALITY)),

    PPTX_PDF(Group.PPTX, Kind.PPTX, Kind.PDF, R.string.convert_pptx_pdf, R.string.convert_pptx_pdf_desc),
    PPTX_IMAGES(Group.PPTX, Kind.PPTX, Kind.IMAGE, R.string.convert_pptx_images, R.string.convert_pptx_images_desc,
        options = setOf(Opt.FORMAT2, Opt.SCALE)),
    PPTX_TXT(Group.PPTX, Kind.PPTX, Kind.TEXT, R.string.convert_pptx_txt, R.string.convert_pptx_txt_desc),

    DOCX_PDF(Group.WORD, Kind.DOCX, Kind.PDF, R.string.convert_docx_pdf, R.string.convert_docx_pdf_desc),
    DOCX_TXT(Group.WORD, Kind.DOCX, Kind.TEXT, R.string.convert_docx_txt, R.string.convert_docx_txt_desc),

    NOTE_PDF(Group.NOTES, Kind.NOTE, Kind.PDF, R.string.convert_note_pdf, R.string.convert_note_pdf_desc),
    NOTE_IMAGES(Group.NOTES, Kind.NOTE, Kind.IMAGE, R.string.convert_note_images, R.string.convert_note_images_desc,
        options = setOf(Opt.FORMAT2, Opt.QUALITY)),
    NOTE_DOCX(Group.NOTES, Kind.NOTE, Kind.DOCX, R.string.convert_note_docx, R.string.convert_note_docx_desc),
    /** tags-agent: one self-contained web page to share instead of a link (no server). */
    NOTE_HTML(Group.NOTES, Kind.NOTE, Kind.OTHER, R.string.tags_conv_note_html, R.string.tags_conv_note_html_desc, toExt = "html"),
    TEXT_PDF(Group.NOTES, Kind.TEXT, Kind.PDF, R.string.convert_text_pdf, R.string.convert_text_pdf_desc),
    TEXT_DOCX(Group.NOTES, Kind.TEXT, Kind.DOCX, R.string.convert_text_docx, R.string.convert_text_docx_desc),
    ONE_PDF(Group.NOTES, Kind.ONENOTE, Kind.PDF, R.string.convert_one_pdf, R.string.convert_one_pdf_desc),
    ONE_TXT(Group.NOTES, Kind.ONENOTE, Kind.TEXT, R.string.convert_one_txt, R.string.convert_one_txt_desc),
    ONE_NOTE(Group.NOTES, Kind.ONENOTE, Kind.NOTE, R.string.convert_one_note, R.string.convert_one_note_desc),

    MERGE_PDF(Group.TOOLS, Kind.PDF, Kind.PDF, R.string.convert_merge, R.string.convert_merge_desc, multi = true, minSources = 2,
        tool = Icons.AutoMirrored.Rounded.MergeType),
    SPLIT_PDF(Group.TOOLS, Kind.PDF, Kind.PDF, R.string.convert_split, R.string.convert_split_desc,
        options = setOf(Opt.SPLIT), tool = Icons.AutoMirrored.Rounded.CallSplit),
    COMPRESS_PDF(Group.TOOLS, Kind.PDF, Kind.PDF, R.string.convert_compress, R.string.convert_compress_desc,
        options = setOf(Opt.DPI, Opt.QUALITY, Opt.ANNOTATIONS), tool = Icons.Rounded.Compress);

    val icon: ImageVector get() = tool ?: if (this == NOTE_HTML) Icons.Rounded.Language else kindIcon(to)

    fun accepts(f: File): Boolean = f.isFile && Storage.kindOf(f) == from

    /** Choices for the DPI option. */
    val dpiChoices: List<Int> get() = if (this == COMPRESS_PDF) listOf(72, 110, 150, 200) else listOf(72, 150, 220, 300)

    /** Choices for the quality option (JPEG / WEBP quality, percent). */
    val qualityChoices: List<Int> get() = if (this == COMPRESS_PDF) listOf(40, 60, 75, 90) else listOf(60, 75, 90, 100)

    /** Options when nothing was changed. */
    fun defaults(): ConvOptions = when (this) {
        COMPRESS_PDF -> ConvOptions(dpi = 110, quality = 60, format = ImgFmt.JPG)
        IMAGE_FORMAT -> ConvOptions(format = ImgFmt.JPG, quality = 90)
        PDF_PPTX -> ConvOptions(dpi = 150)
        else -> ConvOptions()
    }

    /** True when running needs input that has no sensible default (the sheet opens the options step first). */
    val needsSetup: Boolean get() = this == SPLIT_PDF

    companion object {
        /** Conversions offered for one file of [kind] (merge needs several files, so the sheet links to the hub for it). */
        fun forKind(kind: Kind): List<Conv> = entries.filter { it.from == kind && it.minSources <= 1 }

        fun inGroup(g: Group): List<Conv> = entries.filter { it.group == g }
    }
}

data class ConvOptions(
    /** Page range text ("1-3, 5"); blank = all pages. */
    val range: String = "",
    val format: ImgFmt = ImgFmt.PNG,
    val quality: Int = 90,
    val dpi: Int = 150,
    /** Slide image scale (PowerPoint → images). */
    val scale: Float = 2f,
    val splitMode: SplitMode = SplitMode.EVERY,
    val splitEvery: Int = 1,
    val splitRanges: String = "",
    /** Draw the student's ink layer on rasterized PDF pages. */
    val annotations: Boolean = true,
)

/** A conversion input: a library file, or an image straight from the device photo picker. */
sealed class Src {
    abstract val name: String
    abstract val kind: Kind
    abstract val key: String

    data class Lib(val file: File) : Src() {
        override val name: String get() = file.name
        override val kind: Kind get() = Storage.kindOf(file)
        override val key: String get() = file.absolutePath
    }

    data class Device(val uri: Uri, override val name: String) : Src() {
        override val kind: Kind get() = Kind.IMAGE
        override val key: String get() = uri.toString()
    }

    val baseName: String get() = name.substringBeforeLast('.').ifBlank { name }
    val libFile: File? get() = (this as? Lib)?.file
}

/** What a finished conversion produced. [open] is what "Open" shows (a file, or the output folder). */
data class ConvOutput(
    val files: List<File>,
    val open: File,
    val folder: File,
    val sizeBefore: Long = -1,
    val sizeAfter: Long = -1,
    val skipped: Int = 0,
)

/** A failure with a user-facing (localized) message. */
class ConvertException(@StringRes val msg: Int, vararg val args: Any) : Exception()
