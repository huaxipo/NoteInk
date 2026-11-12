package com.noteink.app.store

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import com.noteink.app.ink.Ink
import com.noteink.app.model.NotebookMeta
import com.noteink.app.model.PageMeta
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 导出与备份。
 *
 * 三条出口：
 *  1. 整本 / 单页导出 PDF（矢量笔迹 + 底图，A4 比例，1:1 页面尺寸）；
 *  2. 导出页面为 PNG（可一次导出整本到一个目录）；
 *  3. 整本打包成 .zip（meta + 底图 + 笔迹），可再导入恢复。
 *
 * 全部通过系统的文件选择器写入，不申请任何存储权限。
 */
object Exporter {

    /** 把一页画到 canvas 上，dst 为输出区域尺寸 */
    fun drawPage(
        store: NotebookStore,
        nb: NotebookMeta,
        page: PageMeta,
        canvas: Canvas,
        dstW: Float,
        dstH: Float
    ): Bitmap? {
        val bg = store.loadPageBitmap(nb, page)
        val sx = dstW / page.width
        val sy = dstH / page.height
        canvas.save()
        canvas.scale(sx, sy)
        if (!bg.isRecycled) {
            canvas.drawBitmap(bg, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null)
        }
        val strokes = store.loadStrokes(nb.id, page.id)
        if (strokes.isNotEmpty()) {
            InkWrap.render(canvas, strokes, page.width.toFloat(), page.height.toFloat())
        }
        canvas.restore()
        return bg
    }

    private object InkWrap {
        fun render(canvas: Canvas, strokes: List<com.noteink.app.model.Stroke>, w: Float, h: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val path = Path()
            Ink.renderAll(canvas, strokes, paint, path, RectF(0f, 0f, w, h))
        }
    }

    // ---------------- PDF ----------------

    /**
     * 导出为 PDF。[out] 由调用方负责关闭。
     * 页面尺寸按 150dpi 像素换算成 point，得到标准 A4（595 x 842pt）。
     */
    fun exportPdf(
        store: NotebookStore,
        nb: NotebookMeta,
        out: OutputStream,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int {
        val doc = PdfDocument()
        try {
            nb.pages.forEachIndexed { i, p ->
                val wPt = (p.width * 72f / 150f).toInt().coerceAtLeast(72)
                val hPt = (p.height * 72f / 150f).toInt().coerceAtLeast(72)
                val info = PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create()
                val page = doc.startPage(info)
                val c = page.canvas
                c.drawColor(Color.WHITE)
                val bg = drawPage(store, nb, p, c, wPt.toFloat(), hPt.toFloat())
                if (bg != null && !bg.isRecycled) bg.recycle()
                doc.finishPage(page)
                onProgress?.invoke(i + 1, nb.pages.size)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return nb.pages.size
    }

    // ---------------- PNG ----------------

    /** 单页导出为 PNG，输出分辨率跟随页面本身（不放大） */
    fun exportPagePng(
        store: NotebookStore,
        nb: NotebookMeta,
        page: PageMeta,
        out: OutputStream
    ) {
        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val bg = drawPage(store, nb, page, c, page.width.toFloat(), page.height.toFloat())
        if (bg != null && !bg.isRecycled) bg.recycle()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    /**
     * 整本导出为一批 PNG，写进用户选定的目录（SAF）。
     * 返回实际写入的数量。
     */
    fun exportAllPng(
        ctx: Context,
        store: NotebookStore,
        nb: NotebookMeta,
        treeUri: Uri,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int {
        val cr: ContentResolver = ctx.contentResolver
        // 规范做法：先由 tree uri 解析出目录的 document uri，再在它下面建文件。
        // 直接把 tree uri 传给 createDocument 在部分 ROM 上会抛 IllegalArgumentException。
        val dirUri = try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        } catch (t: Throwable) {
            treeUri
        }
        var ok = 0
        nb.pages.forEachIndexed { i, p ->
            val name = sanitize("${nb.title}_${(i + 1).toString().padStart(3, '0')}.png")
            var target: Uri? = null
            try {
                target = DocumentsContract.createDocument(cr, dirUri, "image/png", name)
                if (target != null) {
                    cr.openOutputStream(target)?.use { os ->
                        exportPagePng(store, nb, p, os)
                    }
                    ok++
                }
            } catch (t: Throwable) {
                if (target != null) runCatching { DocumentsContract.deleteDocument(cr, target) }
            }
            onProgress?.invoke(i + 1, nb.pages.size)
        }
        return ok
    }

    // ---------------- 备份 ----------------

    private const val BACKUP_MAGIC = "NOTEINK_BACKUP"
    private const val BACKUP_VERSION = 1

    /**
     * 整本打包成 zip：backup.json + meta.json + 所有底图与笔迹。
     * 只打包 meta 里引用到的文件，孤儿文件不会进备份。
     */
    fun backup(store: NotebookStore, nb: NotebookMeta, out: OutputStream) {
        val zos = ZipOutputStream(out.buffered(), Charsets.UTF_8)
        val dir = store.dir(nb.id)
        val header = JSONObject().apply {
            put("magic", BACKUP_MAGIC)
            put("version", BACKUP_VERSION)
            put("title", nb.title)
            put("pageCount", nb.pages.size)
            put("createdAt", System.currentTimeMillis())
        }
        zos.putNextEntry(ZipEntry("backup.json"))
        zos.write(header.toString().toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        val metaFile = File(dir, "meta.json")
        if (metaFile.exists()) {
            zos.putNextEntry(ZipEntry("meta.json"))
            zos.write(metaFile.readBytes())
            zos.closeEntry()
        }
        val written = HashSet<String>()
        for (p in nb.pages) {
            val names = ArrayList<String>()
            names.add("ink_${p.id}.bin")
            p.bgFile?.let { names.add(it) }
            // 源文件（PDF / PPT）可能被多页共享，用去重集合保证只写一次
            p.src?.let { names.add(it) }
            for (n in names) {
                if (!written.add(n)) continue
                val f = File(dir, n)
                if (!f.exists()) continue
                zos.putNextEntry(ZipEntry(n))
                FileInputStream(f).use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
        }
        zos.finish()
        zos.flush()
    }

    /**
     * 从备份 zip 恢复为一个新笔记本（不会覆盖已有笔记）。
     * 失败返回 null，并保证不留下半成品目录。
     */
    fun restore(ctx: Context, store: NotebookStore, src: InputStream): NotebookMeta? {
        val tmp = File(ctx.cacheDir, "restore_${System.currentTimeMillis()}.zip")
        var newId: String? = null
        try {
            FileOutputStream(tmp).use { os -> src.copyTo(os, 64 * 1024) }

            // 先校验头部，避免把随便一个 zip 当备份
            var valid = false
            ZipInputStream(FileInputStream(tmp).buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "backup.json") {
                        val txt = zis.readBytes().toString(Charsets.UTF_8)
                        valid = runCatching {
                            JSONObject(txt).optString("magic") == BACKUP_MAGIC
                        }.getOrDefault(false)
                        break
                    }
                    e = zis.nextEntry
                }
            }
            if (!valid) return null

            val id = "nb_" + System.currentTimeMillis()
            newId = id
            val dir = store.dir(id)
            ZipInputStream(FileInputStream(tmp).buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (!e.isDirectory && name != "backup.json" &&
                        !name.contains("..") && !name.contains('/')
                    ) {
                        FileOutputStream(File(dir, name)).use { os ->
                            zis.copyTo(os, 64 * 1024)
                        }
                    }
                    e = zis.nextEntry
                }
            }

            // 重写 meta：换新 id，修正时间戳
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) {
                store.delete(id)
                return null
            }
            val o = JSONObject(metaFile.readText())
            o.put("id", id)
            o.put("updatedAt", System.currentTimeMillis())
            metaFile.writeText(o.toString())
            val restored = store.list().firstOrNull { it.id == id } ?: return null
            store.writeCover(restored)
            return restored
        } catch (t: Throwable) {
            newId?.let { runCatching { store.delete(it) } }
            return null
        } finally {
            tmp.delete()
        }
    }

    private fun sanitize(name: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
        return if (base.isBlank()) "noteink.png" else base.take(120)
    }
}
