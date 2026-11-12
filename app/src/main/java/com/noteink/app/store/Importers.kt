package com.noteink.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.noteink.app.model.NotebookMeta
import com.noteink.app.model.PageMeta
import com.noteink.app.model.PageStyle
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 多格式导入。所有格式最终都栅格化成页面底图，画布层无需区分来源。
 * 支持：PDF / PNG / JPG / TXT / Markdown / DOCX / PPTX
 *
 * 速度策略：
 *  1. 编码写盘放到固定线程池，与「渲染下一页」流水线重叠；
 *  2. 信号量限制同时在内存里的页面位图数量，避免大文档 OOM；
 *  3. 编码格式按内容自适应：文本/矢量页面存 PNG（无损，文字边缘不会有 JPEG 振铃毛边），
 *     照片类存 JPEG(q90)。文本页的 PNG 反而比 JPEG 更小，因为大片纯色压缩率极高。
 */
object Importers {

    private const val PDF_MAX_PAGES = 400
    private const val TEXT_MAX_PAGES = 300
    private const val TEXT_MAX_CHARS = 600_000
    private const val JPEG_QUALITY = 90

    enum class Kind { PDF, IMAGE, TEXT, DOCX, PPTX, UNKNOWN }

    fun kindOf(name: String, mime: String?): Kind {
        val n = name.lowercase()
        return when {
            n.endsWith(".pdf") || mime == "application/pdf" -> Kind.PDF
            n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                n.endsWith(".webp") || n.endsWith(".bmp") -> Kind.IMAGE
            n.endsWith(".docx") -> Kind.DOCX
            n.endsWith(".pptx") -> Kind.PPTX
            n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown") -> Kind.TEXT
            mime?.startsWith("image/") == true -> Kind.IMAGE
            mime?.startsWith("text/") == true -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
    }

    fun queryName(ctx: Context, uri: Uri, fallback: String): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val v = c.getString(idx)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: fallback
    }

    /**
     * 把 uri 复制到缓存目录，返回本地文件。避免部分 ContentProvider 返回不可 seek 的 fd。
     */
    private fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        val f = File(ctx.cacheDir, "import_${System.currentTimeMillis()}_$safe")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(f).use { out -> input.copyTo(out, 256 * 1024) }
        }
        return f
    }

    /**
     * 导入一个文件到目标笔记本，返回新增页数。失败抛异常。
     * [onProgress] 在后台线程回调 (已完成页数, 预计总页数)。
     */
    fun importInto(
        ctx: Context,
        store: NotebookStore,
        nb: NotebookMeta,
        uri: Uri,
        displayName: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int {
        val kind = kindOf(displayName, ctx.contentResolver.getType(uri))
        val local = copyToCache(ctx, uri, displayName)
        return try {
            when (kind) {
                Kind.PDF -> importPdf(ctx, store, nb, local, onProgress)
                Kind.IMAGE -> importImage(ctx, store, nb, local, onProgress)
                Kind.TEXT -> importText(
                    store, nb, readTextSmart(local),
                    stripMd = displayName.lowercase().endsWith(".md"),
                    onProgress = onProgress
                )
                Kind.DOCX -> importOffice(store, nb, local, true, onProgress)
                Kind.PPTX -> importOffice(store, nb, local, false, onProgress)
                Kind.UNKNOWN -> throw IllegalStateException("不支持的文件类型：$displayName")
            }
        } finally {
            local.delete()
        }
    }

    // ---------------- 页面写盘（流水线） ----------------

    /**
     * 页面落盘器：调用方在主线程顺序 [render] 出位图，编码写盘在后台线程池并行进行。
     * `pages` 的顺序由调用顺序决定，因此并行不影响页序。
     */
    internal class PageSink(
        private val store: NotebookStore,
        private val nb: NotebookMeta,
        private val onProgress: ((Int, Int) -> Unit)?,
        /** 该批页面来自哪个源文件（目前只有 PDF 会填），用于放大时按需重渲染 */
        private val srcName: String? = null
    ) {        private val startIndex = nb.pages.size
        private val pool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )
        /** 最多允许 [MAX_IN_FLIGHT] 张位图同时存在于内存中 */
        private val slots = Semaphore(MAX_IN_FLIGHT)
        private val futures = ArrayList<Future<*>>()
        private var hintedTotal = 0
        private var done = 0

        fun hintTotal(n: Int) {
            hintedTotal = n
        }

        /** 渲染一页并交给后台编码。[block] 返回值会被回收，不要继续持有。 */
        fun render(srcPage: Int = -1, block: () -> Bitmap) {
            slots.acquire()
            val bmp = try {
                block()
            } catch (t: Throwable) {
                slots.release()
                throw t
            }
            submit(bmp, srcPage)
        }

        private fun submit(bmp: Bitmap, srcPage: Int) {
            val pid = nextId()
            val png = textLike(bmp)
            val f = File(store.dir(nb.id), if (png) "bg_$pid.png" else "bg_$pid.jpg")
            // 调用方给了页序号就用它（PPT 的页序由幻灯片顺序决定，不能按导入进度数）
            val srcPageIdx = if (srcName != null) {
                if (srcPage >= 0) srcPage else nb.pages.size - startIndex
            } else -1
            nb.pages.add(
                PageMeta(
                    pid, f.name, bmp.width, bmp.height, PageStyle.BLANK,
                    src = srcName, srcPage = srcPageIdx
                )
            )
            futures.add(pool.submit {
                try {
                    FileOutputStream(f).use { out ->
                        if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        else bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                } finally {
                    bmp.recycle()
                    slots.release()
                }
                synchronized(this) {
                    done++
                    onProgress?.invoke(done, max(hintedTotal, nb.pages.size))
                }
            })
        }

        private fun nextId(): String {
            var idx = nb.pages.size
            var pid = "p$idx"
            while (nb.pages.any { it.id == pid }) {
                idx++
                pid = "p$idx"
            }
            return pid
        }

        fun finish() {
            try {
                for (f in futures) f.get()
            } finally {
                pool.shutdown()
            }
        }

        /** 渲染中途失败时回滚：把已经落盘的页面全部撤掉，避免留下半份文档 */
        fun discard() {
            finish()
            val added = ArrayList(nb.pages.subList(startIndex, nb.pages.size))
            for (p in added) {
                p.bgFile?.let { File(store.dir(nb.id), it).delete() }
            }
            nb.pages.removeAll(added)
        }

        fun count(): Int = nb.pages.size - startIndex

        companion object {
            const val MAX_IN_FLIGHT = 2
        }
    }

    // ---------------- PDF ----------------

    private fun importPdf(
        ctx: Context,
        store: NotebookStore,
        nb: NotebookMeta,
        file: File,
        onProgress: ((Int, Int) -> Unit)?
    ): Int {
        // 留一份原 PDF：放大时直接从矢量源重渲染可见区域，才有真正的清晰度。
        // 名字带时间戳 —— 同一个笔记本可以导入多个 PDF，用固定名会互相覆盖。
        val srcName = keepSource(store, nb, file, "pdf")
        val sink = PageSink(store, nb, onProgress, srcName = srcName)
        // PdfRenderer 内部是串行的：渲染必须在同一线程，但编码并行
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val total = min(renderer.pageCount, PDF_MAX_PAGES)
                    sink.hintTotal(total)
                    val targetW = PageFactory.importCanvasWidth()
                    for (i in 0 until total) {
                        sink.render {
                            val page = renderer.openPage(i)
                            try {
                                val h = (targetW.toFloat() * page.height / page.width)
                                    .toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
                                val c = Canvas(bmp)
                                c.drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            } finally {
                                page.close()
                            }
                        }
                    }
                }
            }
        } finally {
            sink.finish()
        }
        return sink.count()
    }

    /**
     * 把源文件在笔记本目录里留一份，供放大时按需重渲染（矢量/文本重排，而不是放大位图）。
     * 名字唯一：同一个笔记本可以导入多个源文件，固定名会互相覆盖、把老笔记的高清源弄坏。
     */
    private fun keepSource(store: NotebookStore, nb: NotebookMeta, file: File, ext: String): String? {
        return runCatching {
            val name = "src_${System.currentTimeMillis()}.$ext"
            val dst = File(store.dir(nb.id), name)
            file.copyTo(dst, overwrite = false)
            if (dst.length() > 0L) name else null
        }.getOrNull()
    }

    // ---------------- 图片 ----------------

    private fun importImage(
        ctx: Context,
        store: NotebookStore,
        nb: NotebookMeta,
        file: File,
        onProgress: ((Int, Int) -> Unit)?
    ): Int {
        val sink = PageSink(store, nb, onProgress)
        sink.hintTotal(1)
        try {
            sink.render { decodeImagePage(file) }
        } finally {
            sink.finish()
        }
        return sink.count()
    }

    private fun decodeImagePage(file: File): Bitmap {
        val targetW = 1400
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val sw = max(1, opts.outWidth)

        var sample = 1
        while (sw / (sample * 2) >= targetW) sample *= 2

        val dec = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var src = BitmapFactory.decodeFile(file.absolutePath, dec)
            ?: throw IllegalStateException("图片解码失败")

        val rot = readExifRotation(file)
        if (rot != 0) {
            val m = android.graphics.Matrix()
            m.postRotate(rot.toFloat())
            val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (r !== src) src.recycle()
            src = r
        }

        val outW = min(src.width, targetW)
        val outH = (outW.toFloat() * src.height / src.width).toInt().coerceAtLeast(1)
        if (src.width == outW && src.height == outH) return src

        val page = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, null, android.graphics.RectF(0f, 0f, outW.toFloat(), outH.toFloat()), null)
        src.recycle()
        return page
    }

    @Suppress("DEPRECATION")
    private fun readExifRotation(file: File): Int {
        return try {
            val exif = android.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (t: Throwable) {
            0
        }
    }

    // ---------------- 纯文本 ----------------

    private fun readTextSmart(file: File): String {
        val bytes = file.readBytes()
        var s = String(bytes, Charsets.UTF_8)
        val bad = s.count { it == '\uFFFD' }
        if (bad > 0 && bad > s.length / 200) {
            s = runCatching { String(bytes, charset("GBK")) }.getOrDefault(s)
        }
        return s.take(TEXT_MAX_CHARS)
    }

    /** 简单去掉 Markdown 标记，保留可读正文 */
    private fun stripMarkdown(src: String): String {
        var s = src
        s = s.replace(Regex("```[\\s\\S]*?```"), "")
        s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        return s
    }

    private fun importText(
        store: NotebookStore,
        nb: NotebookMeta,
        raw: String,
        stripMd: Boolean,
        onProgress: ((Int, Int) -> Unit)?
    ): Int {
        var text = if (stripMd) stripMarkdown(raw) else raw
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) text = "（文件内容为空）"
        return renderTextPages(store, nb, text, onProgress)
    }

    private fun renderTextPages(
        store: NotebookStore,
        nb: NotebookMeta,
        text: String,
        onProgress: ((Int, Int) -> Unit)?
    ): Int {
        val w = PageFactory.importCanvasWidth()
        val h = PageFactory.importCanvasHeight()
        // 字号、行距、页边距都按画布宽度等比放大，视觉效果与 1240 宽时一致
        val k = w / PageFactory.DEFAULT_W.toFloat()
        val margin = 92f * k
        val contentW = (w - margin * 2).toInt()
        val contentH = (h - margin * 2).toInt()

        val tp = TextPaint().apply {
            isAntiAlias = true
            textSize = 34f * k
            color = 0xFF232323.toInt()
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, tp, contentW)
            .setLineSpacing(6f * k, 1.28f)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        val pages = min(ceil(layout.height.toFloat() / contentH).toInt(), TEXT_MAX_PAGES)
        val sink = PageSink(store, nb, onProgress)
        sink.hintTotal(pages)
        try {
            repeat(pages) { i ->
                sink.render {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    c.drawColor(PageFactory.paperColor())
                    c.save()
                    c.clipRect(margin.toInt(), margin.toInt(), w - margin.toInt(), h - margin.toInt())
                    c.translate(margin, margin - i * contentH)
                    layout.draw(c)
                    c.restore()
                    bmp
                }
            }
        } finally {
            sink.finish()
        }
        return sink.count()
    }

    // ---------------- Word ----------------

    private fun extractDocx(file: File): String {
        var xml: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
                e = zip.nextEntry
            }
        }
        val doc = xml ?: throw IllegalStateException("这不是有效的 docx 文件")
        // 段落 -> 换行，再抽取 <w:t> 文本
        val withBreaks = doc.replace("</w:p>", "\n").replace("<w:br/>", "\n")
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val s = withBreaks.indexOf("<w:t", i)
            if (s < 0) break
            val gt = withBreaks.indexOf('>', s)
            if (gt < 0) break
            val e = withBreaks.indexOf("</w:t>", gt)
            if (e < 0) break
            sb.append(unescapeXml(withBreaks.substring(gt + 1, e)))
            i = e + 6
        }
        // 按 </w:p> 切分保留段落空行
        val paras = withBreaks.split("</w:p>")
        val out = StringBuilder()
        for (p in paras) {
            val ts = Regex("<w:t[^>]*>([\\s\\S]*?)</w:t>").findAll(p).joinToString("") {
                unescapeXml(it.groupValues[1])
            }
            if (ts.isNotBlank()) out.append(ts).append('\n')
        }
        val result = out.toString().trim()
        return if (result.isBlank()) sb.toString().trim() else result
    }

    // ---------------- Office（Word / PowerPoint） ----------------

    /**
     * Office 导入。优先走本地保真渲染；解析失败（异构 XML、超限、加密文档）时
     * 回退到「抽取文字重排」，保证至少不会整个导入失败。
     */
    private fun importOffice(
        store: NotebookStore,
        nb: NotebookMeta,
        file: File,
        isDocx: Boolean,
        onProgress: ((Int, Int) -> Unit)?
    ): Int {
        // PPT 同样留一份源文件：PPT 页是文字/矢量画出来的，放大时按视口重渲染才不会有马赛克
        val srcName = if (isDocx) null else keepSource(store, nb, file, "pptx")
        val sink = PageSink(store, nb, onProgress, srcName = srcName)
        // 与 PDF 用同一个画布宽度：导入页的底图分辨率和屏幕宽度对齐，fit 显示时是缩小而不是放大
        val iw = PageFactory.importCanvasWidth()
        val ih = PageFactory.importCanvasHeight()
        try {
            if (isDocx) {
                OfficeRender.renderDocx(file, sink, iw, ih)
            } else {
                OfficeRender.renderPptx(file, sink, iw)
            }
            sink.finish()
            if (sink.count() > 0) return sink.count()
        } catch (t: Throwable) {
            sink.discard()
        }
        return importText(
            store, nb,
            if (isDocx) extractDocx(file) else extractPptxText(file),
            stripMd = false,
            onProgress = onProgress
        )
    }

    /** 兜底：只抽文字。用于保真渲染失败时，保证内容不丢 */
    private fun extractPptxText(file: File): String {
        val slides = sortedMapOf<Int, String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            val re = Regex("^ppt/slides/slide(\\d+)\\.xml$")
            while (e != null) {
                val m = re.find(e.name)
                if (m != null) {
                    val n = m.groupValues[1].toIntOrNull() ?: 0
                    slides[n] = zip.readBytes().toString(Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        if (slides.isEmpty()) throw IllegalStateException("这不是有效的 pptx 文件")
        val out = StringBuilder()
        for ((_, xml) in slides) {
            for (p in xml.split("</a:p>")) {
                val t = Regex("<a:t>([\\s\\S]*?)</a:t>").findAll(p).joinToString("") {
                    unescapeXml(it.groupValues[1])
                }
                if (t.isNotBlank()) out.append(t).append('\n')
            }
            out.append('\n')
        }
        return out.toString().trim()
    }

    /**
     * 判断这页是不是「文本 / 矢量类」内容。
     *
     * 抽样缩到 128 宽后统计量化颜色数：文字页与幻灯片只有黑白灰加少量主题色（几十种），
     * 照片和扫描件动辄上千种。文本页用 PNG 编码既无损又更小；照片仍用 JPEG。
     */
    private fun textLike(bmp: Bitmap): Boolean {
        if (bmp.width <= 0 || bmp.height <= 0) return false
        val sw = 128
        val sh = max(1, (sw.toFloat() * bmp.height / bmp.width).toInt())
        val small = try {
            // 最近邻缩样即可：只统计颜色多样性，不需要好看
            Bitmap.createScaledBitmap(bmp, sw, sh, false)
        } catch (t: Throwable) {
            return false
        }
        return try {
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val seen = HashSet<Int>(256)
            for (c in px) {
                val q = ((c shr 19) and 0x1F shl 10) or
                    ((c shr 11) and 0x1F shl 5) or
                    ((c shr 3) and 0x1F)
                seen.add(q)
                if (seen.size > 640) return false
            }
            true
        } catch (t: Throwable) {
            false
        } finally {
            if (small !== bmp) small.recycle()
        }
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
